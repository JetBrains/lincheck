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
import kotlin.math.abs
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
    // Threshold for repeated signature iterations before switching threads for await loops.
    val awaitSwitchThreshold: Int = 3,
    // Threshold for consecutive CAS failures before switching threads for CAS loops.
    val casSwitchThreshold: Int = 5,
    // Threshold for repeated signatures or cycle repetition before switching threads for unknown loops.
    val defaultSwitchThreshold: Int = 10,
    // Fallback limit to declare stuck. Used regardless of loop classification
    val iterationBoundThreshold: Int = 200,
    // Threshold for abstract state revisits before declaring STUCK.
    val stuckThreshold: Int = 60,
    // Threshold for recursive method calls.
    val recursiveCallsBound: Int = 50,
) : LoopDetector {

    private val threadStates = mutableThreadMapOf<LoopDetectorThreadState>()
    // Per-thread stack of currently active loops
    private val activeLoopStack = mutableThreadMapOf<ArrayDeque<LoopContext>>()

    // Per-loop adaptive state, keyed by (threadId, loopId, codeLocation)
    private val loopInstances = mutableThreadMapOf<MutableMap<LoopKey, LoopInstanceState>>()

    // Global write version counter for external-write tracking
    private var globalWriteVersion: Long = 0L

    // Last write version per location, for detecting relevant external writes
    private val locationWriteVersions = mutableMapOf<Int, Long>()

    // Thread that performed the last write to each location
    private val locationWriteThread = mutableMapOf<Int, Int>()

    private fun loopStack(thread: Int): ArrayDeque<LoopContext> =
        activeLoopStack.getOrPut(thread) { ArrayDeque() }

    override fun resetAll() {
        threadStates.clear()
        activeLoopStack.clear()
        loopInstances.clear()
        globalWriteVersion = 0L
        locationWriteVersions.clear()
        locationWriteThread.clear()
    }

    override fun resetThread(threadId: Int) {
        threadStates.remove(threadId)
        activeLoopStack.remove(threadId)
        loopInstances.remove(threadId)
    }

    private fun state(threadId: Int): LoopDetectorThreadState =
        threadStates.getOrPut(threadId) {
            LoopDetectorThreadState(threadId)
        }

    override fun currentMethodId(threadId: Int): Int {
        val st = threadStates[threadId] ?: return -1
        return st.callStack.lastOrNull()?.methodId ?: -1
    }

    override fun loopIsInStack(threadId: Int, loopId: Int): Boolean {
        val stack = activeLoopStack[threadId] ?: return false
        return stack.any { it.loopId == loopId }
    }

    private fun instances(threadId: Int): MutableMap<LoopKey, LoopInstanceState> =
        loopInstances.getOrPut(threadId) { mutableMapOf() }

    private fun getOrCreateInstance(threadId: Int, loopId: Int, codeLocation: Int): LoopInstanceState {
        val key = LoopKey(loopId, codeLocation)
        return instances(threadId).getOrPut(key) { LoopInstanceState(ownerThreadId = threadId) }
    }

    private fun currentInstance(threadId: Int): LoopInstanceState? {
        val stack = activeLoopStack[threadId] ?: return null
        val top = stack.lastOrNull() ?: return null
        val key = LoopKey(top.loopId, top.codeLocation)
        return instances(threadId)[key]
    }

    override fun getCurrentIteration(threadId: Int, loopId: Int, codeLocation: Int): Int {
        val key = LoopKey(loopId, codeLocation)
        return instances(threadId)[key]?.iterNumber ?: 0
    }

    // --- LOOP LEVEL ---
    override fun beforeLoopEnter(threadId: Int, codeLocation: Int, loopId: Int) {
        val st = state(threadId)
        val frame = st.callStack.lastOrNull()
            ?: ActiveMethodCallInfo(methodId = -1).also { st.callStack.addLast(it) }
        frame.loops.addLast(ActiveLoopInfo(LoopKey(loopId, codeLocation)))
    }

    override fun onLoopIteration(
        threadId: Int,
        codeLocation: Int,
        loopId: Int
    ): Pair<Boolean, LoopDetector.Decision> {
        val methodId = currentMethodId(threadId)
        val stack = loopStack(threadId)

        val loop = stack.lastOrNull { it.loopId == loopId && it.codeLocation == codeLocation && it.methodId == methodId }
        val started = if (loop == null) {
            beforeLoopEnter(threadId, codeLocation, loopId)
            stack.addLast(LoopContext(loopId, methodId, codeLocation))
            true
        } else {
            false
        }

        val inst = getOrCreateInstance(threadId, loopId, codeLocation)
        val decision = processIteration(inst, threadId)
        return Pair(started, decision)
    }

    override fun onAwaitLoop(
        threadId: Int,
        codeLocation: Int,
        loopId: Int
    ): Pair<Boolean, LoopDetector.Decision> {
        // classify the loop as await and call onLoopIteration
        val inst = getOrCreateInstance(threadId, loopId, codeLocation)
        if (inst.kind == LoopKind.UNKNOWN) {
            inst.kind = LoopKind.AWAIT
        }
//        println("Classified loop ${inst.ownerThreadId}:${inst.signatureHistory.joinToString(",")} as ${inst.kind}")
        return onLoopIteration(threadId, codeLocation, loopId)
    }

    override fun afterLoopExit(
        threadId: Int,
        codeLocation: Int,
        loopId: Int,
        isReachableFromOutsideLoop: Boolean
    ): Int? {
        if (!isReachableFromOutsideLoop || loopIsInStack(threadId, loopId)) {
            val methodId = currentMethodId(threadId)
            val stack = loopStack(threadId)

            val indexToRemove = stack.withIndex()
                .filter { it.value.loopId == loopId && it.value.methodId == methodId }
                .minByOrNull { abs(it.value.codeLocation - codeLocation) }
                ?.index
            val loop = if (indexToRemove != null) {
                val temp = ArrayDeque<LoopContext>()
                while (stack.size - 1 > indexToRemove) temp.addFirst(stack.removeLast())
                val found = stack.removeLast()
                for (l in temp) stack.addLast(l)
                found
            } else null

            val enterCodeLocation = loop?.codeLocation ?: codeLocation
            val key = LoopKey(loopId, enterCodeLocation)
            instances(threadId).remove(key)
            removeLoopFromStack(threadId, loopId)

            return enterCodeLocation
        }
        return null
    }

    private fun removeLoopFromStack(threadId: Int, loopId: Int) {
        val st = state(threadId)
        val stack = st.callStack
        val frame = stack.lastOrNull() ?: return

        val loop = frame.loops.lastOrNull { it.key.loopId == loopId } ?: return
        loop.iterationCount = 0
        if (frame.loops.lastOrNull() === loop) {
            frame.loops.removeLast()
        } else {
            val idx = frame.loops.indexOfLast { it.key.loopId == loopId }
            if (idx != -1) frame.loops.removeAt(idx)
        }
    }

    // --- Decision logic ---

    // Compute loop signature, classify loop, and make decision
    private fun processIteration(inst: LoopInstanceState, threadId: Int): LoopDetector.Decision {
        if (inst.iterNumber > 0) {
            // upper threshold: we declare stuck after too many iterations
            if (inst.iterNumber >= iterationBoundThreshold) {
//                println("Declaring STUCK on thread ${inst.ownerThreadId} at iteration ${inst.iterNumber} due to hard iteration bound")
                return LoopDetector.Decision.STUCK
            }

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

                if (inst.repeatCount > 0 || hasCycle(inst.signatureHistory)) {
                    inst.waitSetCandidates.addAll(inst.obs.reads.keys)
                }

                classifyLoop(inst)

                // Start making decisions after we pass the minimum iteration threshold
                // in order to avoid premature switching for short loops.
                if (inst.iterNumber >= minIterationsBeforeSwitch) {
                    val wsHash = computeWaitSetHash(inst)
                    inst.abstractStateHash = wsHash
                    val visits = (inst.abstractStateVisits[wsHash] ?: 0) + 1
                    inst.abstractStateVisits[wsHash] = visits

                    val hasRelevantWrite = inst.waitSetCandidates.any { loc ->
                        (locationWriteVersions[loc] ?: 0L) > inst.lastRelevantWriteVersion &&
                            (locationWriteThread[loc] ?: inst.ownerThreadId) != inst.ownerThreadId
                    }
//                    println("hasRelevantWrite=$hasRelevantWrite for thread ${inst.ownerThreadId} at iteration ${inst.iterNumber} with waitSetCandidates ${inst.waitSetCandidates}")
                    if (hasRelevantWrite) {
//                        println("globalWriteVersion: $globalWriteVersion, locationWriteVersions: ${locationWriteVersions.filterKeys { it in inst.waitSetCandidates }}")
                        inst.lastRelevantWriteVersion = globalWriteVersion
                        inst.abstractStateVisits.clear()
                        inst.switchCountPerAbstractState.clear()
                        inst.maxEnabledPerAbstractState.clear()
                    }

                    val decision = makeDecision(inst, visits, hasRelevantWrite)
                    if (decision != LoopDetector.Decision.IDLE) {
                        inst.iterNumber++
                        inst.obs.clear()
                        return decision
                    }
                }
            } else {
                // If there are no observations, there is no basis for signature decisions,
                // we just switch after each [minIterationsBeforeSwitch] number of iterations to prevent infinite spinning
                if (inst.iterNumber >= minIterationsBeforeSwitch &&
                    inst.iterNumber % minIterationsBeforeSwitch == 0) {
                    inst.iterNumber++
                    inst.obs.clear()
                    return LoopDetector.Decision.SWITCH_THREAD
                }
            }
        }

        // Start new iteration
        inst.iterNumber++
        for ((loc, valHash) in inst.obs.reads) {
            inst.lastSeenWSValues[loc] = valHash
        }
        inst.obs.clear()
        return LoopDetector.Decision.IDLE
    }

    private fun classifyLoop(inst: LoopInstanceState) {
        // do no classify if we already classified or if we didnt complete enough iterations
        if (inst.kind != LoopKind.UNKNOWN || inst.iterNumber < 3) return

        when {
            inst.totalCasFailures >= 2 && inst.repeatCount > 0 -> inst.kind = LoopKind.CAS
            inst.obs.writes.isNotEmpty() && hasCycle(inst.signatureHistory) -> inst.kind = LoopKind.ZNE
        }

//        println("Classified loop ${inst.ownerThreadId}:${inst.signatureHistory.joinToString(",")} as ${inst.kind}")
    }

    private fun makeDecision(
        inst: LoopInstanceState,
        abstractStateVisits: Int,
        hasRelevantWrite: Boolean
    ): LoopDetector.Decision {
        // Check if we should declare stuck: the same abstract state was revisited many times
        // with no external progress, and all other available threads were explored.
        // If there are enabled threads that could make progress,
        // we keep switching and rely on the upper threshold as the backup.
        if (abstractStateVisits >= stuckThreshold && !hasRelevantWrite) {
            val absHash = inst.abstractStateHash
            val maxEnabled = inst.maxEnabledPerAbstractState[absHash] ?: 0
            if (maxEnabled == 0) {
//                println("Declaring STUCK on thread ${inst.ownerThreadId} at abstract state $absHash after $abstractStateVisits visits with no relevant writes and no enabled threads")
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
                if (inst.repeatCount >= casSwitchThreshold)
                    return LoopDetector.Decision.SWITCH_THREAD
            }
            // TODO: think of another way to handle zne
            LoopKind.ZNE, LoopKind.UNKNOWN -> {
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

    // --- METHOD LEVEL ---
    override fun onMethodEnter(
        threadId: Int,
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ): LoopDetector.Decision {
        val st = state(threadId)
        val stack = st.callStack
        val top = stack.lastOrNull()

        val counters = st.methodCallCounters
        val count = (counters[methodId] ?: 0) + 1
        counters[methodId] = count
        if (count > recursiveCallsBound) {
            return LoopDetector.Decision.STUCK
        }

        if (top != null && top.methodId == methodId) {
            top.depth += 1
            if (top.depth > recursiveCallsBound) {
                return LoopDetector.Decision.STUCK
            }
        } else {
            stack.addLast(ActiveMethodCallInfo(methodId = methodId))
        }

        return LoopDetector.Decision.IDLE
    }

    override fun onMethodExit(
        threadId: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?
    ) {
        val st = state(threadId)
        val stack = st.callStack
        val top = stack.lastOrNull() ?: return

        val counters = st.methodCallCounters
        val count = (counters[methodId] ?: 1) - 1
        if (count <= 0) counters.remove(methodId) else counters[methodId] = count

        if (top.methodId == methodId) {
            if (top.depth > 1) top.depth -= 1 else stack.removeLast()
            return
        }

        val idx = stack.indexOfLast { it.methodId == methodId }
        if (idx == -1) return
        while (stack.size - 1 > idx) stack.removeLast()
        val frame = stack.lastOrNull() ?: return
        if (frame.depth > 1) frame.depth -= 1 else stack.removeLast()
    }

    // --- IRREDUCIBLE LOOPS ---
    override fun onIrreducibleLoop(threadId: Int, codeLocation: Int): LoopDetector.Decision {
        val inst = getOrCreateInstance(threadId, -2, codeLocation)
        return processIteration(inst, threadId)
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
        if (success) inst.obs.casSuccesses++ else inst.obs.casFailures++
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

        // Increment the switch count for current abstract state
        inst.switchCountPerAbstractState[absHash] =
            (inst.switchCountPerAbstractState[absHash] ?: 0) + 1

        // Track the maximum number of enabled threads, we ignore the current thread.
        val otherEnabled = enabledThreads.count { it != threadId }
        val prevMax = inst.maxEnabledPerAbstractState[absHash] ?: 0
        if (otherEnabled > prevMax) {
            inst.maxEnabledPerAbstractState[absHash] = otherEnabled
        }
    }
}