/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.util.mutableThreadMapOf
//TODO: check for unused function parameters

/**
 * Loop detector that classifies loops based on observed shared-memory operations
 * and makes switching/stuck decisions accordingly.
 *
 * Loop kinds:
 *  - AWAIT: loop reads shared state, waiting for an external change. Switch quickly. Identified during instrumentation
 *  - CAS:   loop loses CAS races. Switch on repeated failures
 *  - ZNE:   loop writes cancel out (zero net effect). Switch on signature cycles
 *  - UNKNOWN: fallback, uses signature repetition / cycle thresholds
 *
 * Stuck is declared when the same abstract state (wait-set values + enabled threads)
 * is revisited multiple times with no relevant progress (external writes)
 */
class AdaptiveLoopDetector(
    // Minimum number of iterations before any switching decision can be done
    val minIterationsBeforeSwitch: Int = 10,
    // Threshold for repeated side effect free back-edges needed before classifying the loop as AWAIT.
    val awaitClassificationThreshold: Int = 3,
    // Threshold for repeated signature iterations before switching threads for await loops.
    val awaitSwitchThreshold: Int = 3,
    // Threshold for consecutive CAS failures before switching threads for CAS loops.
    val casSwitchThreshold: Int = 5,
    // Threshold for repeated signatures or cycle repetition before switching threads for unknown loops.
    val defaultSwitchThreshold: Int = 10,
    // Threshold for repeated signature cycles before switching threads for ZNE loops.
    val zneSwitchThreshold: Int = 3,
    // Fallback limit to declare stuck. Used regardless of loop classification
    val iterationBoundThreshold: Int = 50,
    // Threshold for abstract state revisits before declaring STUCK.
    val stuckThreshold: Int = 30,
    // Threshold for recursive method calls.
    override val recursiveCallsBound: Int = 50,
) : AbstractLoopDetector() {

    // Per-loop adaptive state, keyed by (threadId, loopId, codeLocation)
    private val loopInstances = mutableThreadMapOf<MutableMap<LoopKey, LoopInstanceState>>()

    // Global write version counter for external-write tracking
    private var globalWriteVersion: Long = 0L

    // Last write version per location, for detecting relevant external writes
    private val locationWriteVersions = mutableMapOf<Int, Long>()

    // Thread that performed the last write to each location
    private val locationWriteThread = mutableMapOf<Int, Int>()

    // Last successful CAS, external progress evidence when the precise CAS location is unavailable
    private var globalCasSuccessVersion: Long = 0L
    private var globalCasSuccessThread: Int = -1

    private fun findActiveLoop(threadId: Int): ActiveLoopInfo? {
        val state = threadStates[threadId] ?: return null
        for (frame in state.callStack.reversed()) {
            val loop = frame.loops.lastOrNull()
            if (loop != null) return loop
        }
        return null
    }

    override fun resetAll() {
        super.resetAll()
        loopInstances.clear()
        globalWriteVersion = 0L
        locationWriteVersions.clear()
        locationWriteThread.clear()
        globalCasSuccessVersion = 0L
        globalCasSuccessThread = -1
    }

    override fun resetThread(threadId: Int) {
        super.resetThread(threadId)
        loopInstances.remove(threadId)
    }

    private fun instances(threadId: Int): MutableMap<LoopKey, LoopInstanceState> =
        loopInstances.getOrPut(threadId) { mutableMapOf() }

    private fun getOrCreateInstance(threadId: Int, loopId: Int, codeLocation: Int): LoopInstanceState {
        val key = LoopKey(loopId, codeLocation)
        return instances(threadId).getOrPut(key) { LoopInstanceState(ownerThreadId = threadId) }
    }

    private fun currentInstance(threadId: Int): LoopInstanceState? {
        val loop = findActiveLoop(threadId) ?: return null
        return instances(threadId)[loop.key]
    }

    // --- LOOP LEVEL ---
    override fun onLoopIteration(
        threadId: Int,
        codeLocation: Int,
        loopId: Int
    ): Pair<Boolean, LoopDetector.Decision> {
        val (loop, started) = startLoopIfNeeded(threadId, codeLocation, loopId)
        loop.iterationCount += 1

        val inst = getOrCreateInstance(threadId, loopId, codeLocation)
        val decision =
            if (inst.nextIterationHandledAtAwaitBackEdge) {
                inst.nextIterationHandledAtAwaitBackEdge = false
                inst.currentIterationStarted = false
                LoopDetector.Decision.IDLE // we already made the decision at the back-edge, do not process again
            } else {
                inst.consecutiveAwaitBackEdgeHits = 0
                inst.currentIterationStarted = true
                processIteration(inst)
            }
        return Pair(started, decision)
    }

    override fun onAwaitLoopPathIteration(
        threadId: Int,
        codeLocation: Int,
        loopId: Int
    ): LoopDetector.Decision {
        // classify the loop as await and process the completed iteration at the back-edge
        val inst = getOrCreateInstance(threadId, loopId, codeLocation)
        inst.consecutiveAwaitBackEdgeHits ++

        if (inst.kind == LoopKind.UNKNOWN && inst.iterNumber >= 3 && inst.consecutiveAwaitBackEdgeHits > awaitClassificationThreshold) {
            inst.kind = LoopKind.AWAIT
            inst.requiresExternalProgress = true
        }
//        println("Classified loop ${inst.ownerThreadId}:${inst.signatureHistory.joinToString(",")} as ${inst.kind}")
        inst.waitSetCandidates.addAll(inst.obs.reads.keys)
        val decision = if (inst.currentIterationStarted) {
            inst.currentIterationStarted = false
            processIteration(inst, advanceIteration = false)
        } else {
            processIteration(inst)
        }
        inst.nextIterationHandledAtAwaitBackEdge = true
        return decision
    }

    override fun afterLoopExit(
        threadId: Int,
        codeLocation: Int,
        loopId: Int,
        isReachableFromOutsideLoop: Boolean
    ): Int? {
        val enterCodeLocation = super.afterLoopExit(threadId, codeLocation, loopId, isReachableFromOutsideLoop)
        if (enterCodeLocation != null) {
            instances(threadId).remove(LoopKey(loopId, enterCodeLocation))
        }
        return enterCodeLocation
    }

    // --- Decision logic ---

    // Compute loop signature, classify loop, and make decision
    private fun processIteration(
        inst: LoopInstanceState,
        advanceIteration: Boolean = true
    ): LoopDetector.Decision {
        if (inst.iterNumber > 0) {
            // Accumulate CAS failures across iterations for classification
            inst.totalCasFailures += inst.obs.casFailures

            // Check whether the iteration produced any shared-memory observations
            val hasObservations = inst.obs.reads.isNotEmpty() ||
                inst.obs.writes.isNotEmpty() ||
                inst.obs.casSuccesses > 0 ||
                inst.obs.casFailures > 0

            if (hasObservations) {
                // signature analysis
                val signature = inst.obs.signature()

                inst.signatureHistory.addLast(signature)
                if (inst.signatureHistory.size > LoopInstanceState.SIGNATURE_HISTORY_SIZE) {
                    inst.signatureHistory.removeFirst()
                }

                inst.repeatCount = if (signature == inst.lastSignature) inst.repeatCount + 1 else 0
                inst.lastSignature = signature

                // Track write only signature for ZNE detection. we count consecutive iterations where same locations are written with same values
                if (inst.obs.writes.isNotEmpty()) {
                    val writeSignature = inst.obs.writeSignature()
                    inst.staleWriteCount = if (writeSignature == inst.lastWriteSignature) inst.staleWriteCount + 1 else 0
                    inst.lastWriteSignature = writeSignature
                }  else {
                    inst.staleWriteCount = 0
                }

                if (inst.repeatCount > 0 || hasCycle(inst.signatureHistory)) {
                    inst.waitSetCandidates.addAll(inst.obs.reads.keys)
                }

                classifyLoop(inst)
                updateExternalProgressRequirement(inst)
                // Start making decisions after we pass the minimum iteration threshold
                // in order to avoid premature switching for short loops.
                if (inst.iterNumber >= minIterationsBeforeSwitch) {
                    val wsHash = computeWaitSetHash(inst)
                    inst.abstractStateHash = wsHash
                    val visits = (inst.abstractStateVisits[wsHash] ?: 0) + 1
                    inst.abstractStateVisits[wsHash] = visits

                    val hasRelevantWrite = checkForRelevantWrite(inst)
//                    println("hasRelevantWrite=$hasRelevantWrite for thread ${inst.ownerThreadId} at iteration ${inst.iterNumber} with waitSetCandidates ${inst.waitSetCandidates}")
                    val decision = makeDecision(inst, visits, hasRelevantWrite)
                    if (decision != LoopDetector.Decision.IDLE) {
                        return finishAndClearIteration(inst, advanceIteration, decision)
                    }
                }
            } else {
                val hasRelevantWrite = checkForRelevantWrite(inst)

                val upperThresholdDecision = makeUpperThresholdDecision(inst, hasRelevantWrite)
                if (upperThresholdDecision != null) {
                    return finishAndClearIteration(inst, advanceIteration, upperThresholdDecision)
                }

                // If there are no observations, there is no basis for signature decisions,
                // we just switch after each [minIterationsBeforeSwitch] number of iterations to prevent infinite spinning
                if (inst.iterNumber >= minIterationsBeforeSwitch && inst.iterNumber % minIterationsBeforeSwitch == 0) {
                    return finishAndClearIteration(inst, advanceIteration, LoopDetector.Decision.SWITCH_THREAD)
                }
            }
        }

        return finishAndClearIteration(inst, advanceIteration, LoopDetector.Decision.IDLE)
    }

    private fun finishAndClearIteration(
        inst: LoopInstanceState,
        advanceIteration: Boolean,
        decision: LoopDetector.Decision
    ): LoopDetector.Decision {
        if (advanceIteration) inst.iterNumber++
        for ((loc, valHash) in inst.obs.reads) {
            inst.lastSeenWSValues[loc] = valHash
        }
        inst.obs.clear()
        return decision
    }

    private fun classifyLoop(inst: LoopInstanceState) {
        // do no classify if we already classified or if we didnt complete enough iterations
        if (inst.kind != LoopKind.UNKNOWN || inst.iterNumber < 3) return

        when {
            inst.totalCasFailures >= 2 && inst.repeatCount > 0 -> inst.kind = LoopKind.CAS
            inst.staleWriteCount >= 2 -> inst.kind = LoopKind.ZNE
        }

//        println("Classified loop ${inst.ownerThreadId}:${inst.signatureHistory.joinToString(",")} as ${inst.kind}")
    }

    private fun checkForRelevantWrite(inst: LoopInstanceState): Boolean {
        val hasRelevantWrite = hasRelevantWrite(inst)
        if (hasRelevantWrite) {
            onRelevantWriteReset(inst)
        }
        return hasRelevantWrite
    }

    private fun hasRelevantWrite(inst: LoopInstanceState): Boolean =
        inst.waitSetCandidates.any { loc ->
            (locationWriteVersions[loc] ?: 0L) > inst.lastRelevantWriteVersion &&
                (locationWriteThread[loc] ?: inst.ownerThreadId) != inst.ownerThreadId
        } || (
            inst.waitSetCandidates.isNotEmpty() &&
                globalCasSuccessVersion > inst.lastRelevantWriteVersion &&
                globalCasSuccessThread != inst.ownerThreadId
        )

    private fun onRelevantWriteReset(inst: LoopInstanceState) {
        inst.lastRelevantWriteVersion = globalWriteVersion
        inst.abstractStateVisits.clear()
        inst.maxEnabledPerAbstractState.clear()
        inst.thresholdSwitchAttempted = false
    }

    private fun makeUpperThresholdDecision(
        inst: LoopInstanceState,
        hasRelevantWrite: Boolean
    ): LoopDetector.Decision? {
        if (hasRelevantWrite || inst.iterNumber < iterationBoundThreshold) return null

        if (!inst.thresholdSwitchAttempted) {
            inst.thresholdSwitchAttempted = true
            return LoopDetector.Decision.SWITCH_THREAD
        }

//        println("Declaring STUCK on thread ${inst.ownerThreadId} at iteration ${inst.iterNumber} due to hard iteration bound")
        return LoopDetector.Decision.STUCK
    }

    private fun updateExternalProgressRequirement(inst: LoopInstanceState) {
        val repeatsObservedState = inst.repeatCount > 0 || hasCycle(inst.signatureHistory)
        if (!repeatsObservedState && inst.kind != LoopKind.AWAIT) return

        val readOnlyIteration =
            inst.obs.reads.isNotEmpty() &&
                inst.obs.writes.isEmpty() &&
                inst.obs.casSuccesses == 0 &&
                inst.obs.casFailures == 0

        val repeatedFailedCas =
            inst.obs.casFailures > 0 &&
                inst.obs.casSuccesses == 0 &&
                inst.obs.writes.isEmpty() &&
                inst.repeatCount >= casSwitchThreshold

        val repeatedSameWrites =
            inst.obs.reads.isNotEmpty() &&
                inst.obs.writes.isNotEmpty() &&
                inst.obs.casSuccesses == 0 &&
                inst.staleWriteCount >= zneSwitchThreshold

        inst.requiresExternalProgress = when {
            inst.kind == LoopKind.AWAIT -> true
            readOnlyIteration -> true
            repeatedFailedCas -> true
            repeatedSameWrites -> true
            inst.obs.writes.isNotEmpty() || inst.obs.casSuccesses > 0 -> false
            else -> inst.requiresExternalProgress
        }
    }

    private fun makeDecision(
        inst: LoopInstanceState,
        abstractStateVisits: Int,
        hasRelevantWrite: Boolean
    ): LoopDetector.Decision {
        val upperThresholdDecision = makeUpperThresholdDecision(inst, hasRelevantWrite)
        if (upperThresholdDecision != null) {
            return upperThresholdDecision
        }
        
        // Check if we should declare stuck: the same abstract state was revisited many times
        // with no external progress, no other thread is availbe to be scheduled
        // and the local execution cannot change the condition of the loop.
        if (inst.requiresExternalProgress &&
            abstractStateVisits >= stuckThreshold &&
            !hasRelevantWrite
        ) {
            val absHash = inst.abstractStateHash
            if (inst.maxEnabledPerAbstractState[absHash] == 0) {
                return LoopDetector.Decision.STUCK
            }
        }

        // Otherwise check the thresholds for each loop kind for switching threads
        when (inst.kind) {
            LoopKind.AWAIT -> {
                if (inst.repeatCount >= awaitSwitchThreshold)
                    return LoopDetector.Decision.SWITCH_THREAD
            }
            LoopKind.CAS -> {
                if (inst.repeatCount >= casSwitchThreshold && inst.totalCasFailures > casSwitchThreshold)
                    return LoopDetector.Decision.SWITCH_THREAD
            }
            LoopKind.ZNE -> {
                if (inst.staleWriteCount >= zneSwitchThreshold)
                    return LoopDetector.Decision.SWITCH_THREAD
            }
            LoopKind.UNKNOWN -> {
                if (hasCycle(inst.signatureHistory) || inst.repeatCount >= defaultSwitchThreshold)
                    return LoopDetector.Decision.SWITCH_THREAD
            }
        }

        return LoopDetector.Decision.IDLE
    }

    /**
     * Check if the last signature appeared earlier in the history.
     * we need at least 3 (for now) elements to avoid false positives on very short loops.
     */
    private fun hasCycle(history: ArrayDeque<Int>): Boolean {
        if (history.size < 3) return false
        val last = history.last()
        for (i in 0 until history.size - 1) {
            if (history[i] == last) return true
        }
        return false
    }

    // Hash function for the values of the wait-set candidate locations.
    private fun computeWaitSetHash(inst: LoopInstanceState): Int {
        var h = 0
        for (loc in inst.waitSetCandidates) {
            val value = inst.obs.reads[loc] ?: inst.lastSeenWSValues[loc] ?: 0
            h = h * 31 + loc.hashCode()
            h = h * 31 + value
        }
        return h
    }

    // --- IRREDUCIBLE LOOPS ---
    override fun onIrreducibleLoopIteration(threadId: Int, codeLocation: Int, loopId: Int): LoopDetector.Decision {
        val inst = getOrCreateInstance(threadId, loopId, codeLocation)

        val state = threadStates[threadId]
        val frame = state?.callStack?.lastOrNull()
        val loop = frame?.loops?.lastOrNull { it.key.loopId == loopId && it.key.codeLocation == codeLocation }
        loop?.iterationCount = (loop?.iterationCount ?: 0) + 1

        return processIteration(inst)
    }

    // --- Callbacks for shared-memory operations --
    override fun onSharedRead(threadId: Int, codeLocation: Int, locationKey: Int, valueHash: Int) {
        val inst = currentInstance(threadId) ?: return
        inst.obs.reads[locationKey] = valueHash
    }

    override fun onSharedWrite(threadId: Int, codeLocation: Int, locationKey: Int, valueHash: Int) {
        val inst = currentInstance(threadId)
        inst?.obs?.writes?.set(locationKey, valueHash)

        globalWriteVersion++
        locationWriteVersions[locationKey] = globalWriteVersion
        locationWriteThread[locationKey] = threadId
    }

    override fun onCasResult(threadId: Int, codeLocation: Int, success: Boolean) {
        val inst = currentInstance(threadId) ?: return
        if (success) {
            inst.obs.casSuccesses++
            globalWriteVersion++
            globalCasSuccessVersion = globalWriteVersion
            globalCasSuccessThread = threadId
        } else {
            inst.obs.casFailures++
        }
    }

    override fun onSwitchedFromLoop(
        threadId: Int,
        loopId: Int,
        codeLocation: Int,
        enabledThreads: Set<Int>
    ) {
        val key = LoopKey(loopId, codeLocation)
        val inst = instances(threadId)[key] ?: return
        val absHash = inst.abstractStateHash

        // Track the maximum number of enabled threads, we ignore the current thread.
        val otherEnabled = enabledThreads.count { it != threadId }
        val prevMax = inst.maxEnabledPerAbstractState[absHash]
        inst.maxEnabledPerAbstractState[absHash] =
            if (prevMax == null) otherEnabled else maxOf(prevMax, otherEnabled)
    }
}