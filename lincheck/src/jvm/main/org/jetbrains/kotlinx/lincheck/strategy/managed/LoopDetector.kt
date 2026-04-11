/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.util.*

/**
 * Tracks loop iterations and recursive method calls
 * during managed strategy execution to detect potential livelocks and infinite recursion.
 *
 * The loop detector is notified by the managed strategy
 * about loop iterations and method enter/exit events.
 * It produces a [Decision] objet that instruct the strategy to either
 *   - [Decision.IDLE] --- continue execution,
 *   - [Decision.SWITCH_THREAD] --- perform a thread switch,
 *   - [Decision.STUCK] --- report a livelock.
 */
interface LoopDetector {
    enum class Decision {
        IDLE,
        SWITCH_THREAD,
        STUCK
    }

    fun beforeLoopEnter(threadId: Int, codeLocation: Int, loopId: Int)
    fun onLoopIteration(threadId: Int, codeLocation: Int, loopId: Int): Pair<Boolean, Decision>
    fun onIrreducibleLoopIteration(threadId: Int, codeLocation: Int, loopId: Int): Decision
    fun onAwaitLoopIteration(threadId: Int, codeLocation: Int, loopId: Int): Pair<Boolean, Decision>
    fun afterLoopExit(threadId: Int, codeLocation: Int, loopId: Int, isReachableFromOutsideLoop: Boolean): Int?

    // TODO: at the moment, method params are only passed, but not used

    fun onMethodEnter(
        threadId: Int,
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
    ) : Decision

    fun onMethodExit(
        threadId: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?,
    )

    fun getCurrentLoopIteration(threadId: Int, loopId: Int, codeLocation: Int) : Int
    fun getCurrentMethodId(threadId: Int): Int

    fun resetAll()                  // between iterations
    fun resetThread(threadId: Int)  // when a thread finishes

    // --- Callbacks for shared-memory operations (used by AdaptiveLoopDetector) ---

    // Called after a shared field/array read inside a loop iteration
    fun onSharedRead(threadId: Int, codeLocation: Int, locationKey: Int, valueHash: Int) {}

    // Called before a shared field/array write inside a loop iteration
    fun onSharedWrite(threadId: Int, codeLocation: Int, locationKey: Int, valueHash: Int) {}

    // Called when a CAS operation completes
    fun onCasResult(threadId: Int, codeLocation: Int, success: Boolean) {}

    /**
     * Called by managed strategy after a thread switch from a loop.
     * Records which thread was switched to and the set of threads that were available,
     * so the detector can determine whether all alternatives have been explored before declaring STUCK
     */
    fun onSwitchedFromLoop(threadId: Int, loopId: Int, codeLocation: Int, enabledThreads: Set<Int>) {}
}

data class LoopKey(
    val loopId: Int,
    val codeLocation: Int,
)

data class ActiveLoopInfo(
    val key: LoopKey,
    var iterationCount: Int = 0,
)

data class ActiveMethodCallInfo(
    val methodId: Int,
    var depth: Int = 1,
    val loops: ArrayDeque<ActiveLoopInfo> = ArrayDeque(),
)

data class LoopDetectorThreadState(
    val threadId: Int,
    val callStack: ArrayDeque<ActiveMethodCallInfo> = ArrayDeque(),
    val methodCallCounters: MutableMap<Int /* MethodId */, Int> = mutableMapOf()
)

// --- Data classes for the adaptive loop detector ---

// Loop classifications
internal enum class LoopKind {
    // Loop reads shared state and waits for an external change.
    AWAIT,
    // Loop repeatedly attempts CAS operations, but losses races with other threads.
    CAS,
    // Loop writes cancel out across iterations (zero net effect).
    ZNE,
    // Not yet classified or doesnt fit other categories.
    UNKNOWN,
}

/**
 * Observations collected during a single loop iteration and cleared after
 */
internal class IterationObservations {
    // locationKey -> last seen valueHash
    val reads = mutableMapOf<Int, Int>()
    // locationKey -> last written valueHash
    val writes = mutableMapOf<Int, Int>()
    var casSuccesses: Int = 0
    var casFailures: Int = 0

    fun clear() {
        reads.clear()
        writes.clear()
        casSuccesses = 0
        casFailures = 0
    }

    // Hash function for combining the observations into a single signature.
    fun signature(): Int {
        var h = reads.hashCode()
        h = h * 31 + writes.hashCode()
        return h
    }

    // Hash of writes, for ZNE detection.
    fun writeSignature(): Int = writes.hashCode()
}

/**
 * Per-loop-instance adaptive state, tracking observations, signatures, and classification.
 */
internal class LoopInstanceState(
    val ownerThreadId: Int,
) {
    var iterNumber: Int = 0
    var kind: LoopKind = LoopKind.UNKNOWN
    val obs = IterationObservations()

    // Signature tracking for cycle detection
    var lastSignature: Int = 0
    var repeatCount: Int = 0
    // limited history of recent signatures
    val signatureHistory = ArrayDeque<Int>()
    companion object {
        const val SIGNATURE_HISTORY_SIZE = 8
    }

    // Wait-set: shared locations whose values influence loop condition
    val waitSetCandidates = mutableSetOf<Int>()
    // locationKey -> last known valueHash at end of iteration
    val lastSeenWSValues = mutableMapOf<Int, Int>()

    // Abstract state for stuck detection
    var abstractStateHash: Int = 0
    // wait set values hash -> abstract state visit count
    val abstractStateVisits = mutableMapOf<Int, Int>()

    // Tracks which threads have been tried from each abstract state,
    // so we only declare STUCK after all alternatives have been explored.

    // Number of SWITCH_THREAD decisions made from each abstract state.
    val switchCountPerAbstractState = mutableMapOf<Int /* abstractStateHash */, Int>()
    // Max number of enabled threads (excluding the thread with the loop) seen from each abstract state.
    val maxEnabledPerAbstractState = mutableMapOf<Int /* abstractStateHash */, Int>()

    // CAS failure count across iterations
    var totalCasFailures: Int = 0

    // track for zne loops
    var lastWriteSignature: Int = 0
    var staleWriteCount: Int = 0

    // External write tracking
    var lastRelevantWriteVersion: Long = 0L
}

abstract class AbstractLoopDetector : LoopDetector {
    protected val threadStates = mutableThreadMapOf<LoopDetectorThreadState>()

    protected abstract val recursiveCallsBound: Int

    override fun resetAll() {
        threadStates.clear()
    }

    override fun resetThread(threadId: Int) {
        threadStates.remove(threadId)
    }

    protected fun state(threadId: Int): LoopDetectorThreadState =
        threadStates.getOrPut(threadId) {
            LoopDetectorThreadState(threadId)
        }

    protected fun currentFrame(threadId: Int): ActiveMethodCallInfo? =
        threadStates[threadId]?.callStack?.lastOrNull()

    protected fun getOrCreateFrame(threadId: Int): ActiveMethodCallInfo {
        val state = state(threadId)
        return state.callStack.lastOrNull() ?: ActiveMethodCallInfo(methodId = -1).also {
            state.callStack.addLast(it)
        }
    }

    override fun getCurrentMethodId(threadId: Int): Int =
        currentFrame(threadId)?.methodId ?: -1

    protected fun findLoopInCurrentFrame(
        threadId: Int,
        loopId: Int,
        codeLocation: Int
    ): ActiveLoopInfo? {
        val frame = currentFrame(threadId) ?: return null
        return frame.loops.lastOrNull {
            it.key.loopId == loopId && it.key.codeLocation == codeLocation
        }
    }

    protected fun startLoopIfNeeded(
        threadId: Int,
        codeLocation: Int,
        loopId: Int
    ): Pair<ActiveLoopInfo, Boolean> {
        var loop = findLoopInCurrentFrame(threadId, loopId, codeLocation)
        val started = if (loop == null) {
            beforeLoopEnter(threadId, codeLocation, loopId)
            loop = findLoopInCurrentFrame(threadId, loopId, codeLocation)
            true
        } else {
            false
        }
        return Pair(loop!!, started)
    }

    protected fun findExitLoop(
        threadId: Int,
        loopId: Int,
        exitCodeLocation: Int
    ): Pair<ActiveMethodCallInfo, ActiveLoopInfo>? {
        val state = threadStates[threadId] ?: return null
        val currentMethod = getCurrentMethodId(threadId)

        var best: Pair<ActiveMethodCallInfo, ActiveLoopInfo>? = null
        var bestDistance = Int.MAX_VALUE

        for (frame in state.callStack.reversed()) {
            if (frame.methodId != currentMethod && frame.methodId != -1) continue

            for (loop in frame.loops.reversed()) {
                if (loop.key.loopId != loopId) continue

                val dist = kotlin.math.abs(loop.key.codeLocation - exitCodeLocation)
                if (dist < bestDistance) {
                    best = Pair(frame, loop)
                    bestDistance = dist
                }
                if (dist == 0)
                    return Pair(frame, loop)
            }
        }
        return best
    }

    override fun beforeLoopEnter(threadId: Int, codeLocation: Int, loopId: Int) {
        val frame = getOrCreateFrame(threadId)
        val existing = frame.loops.lastOrNull {
            it.key.loopId == loopId && it.key.codeLocation == codeLocation
        }
        if (existing == null) {
            frame.loops.addLast(ActiveLoopInfo(LoopKey(loopId, codeLocation)))
        }
    }

    override fun onAwaitLoopIteration(
        threadId: Int,
        codeLocation: Int,
        loopId: Int
    ): Pair<Boolean, LoopDetector.Decision> = onLoopIteration(threadId, codeLocation, loopId)

    override fun afterLoopExit(
        threadId: Int,
        codeLocation: Int,
        loopId: Int,
        isReachableFromOutsideLoop: Boolean
    ): Int? {
        val match = findExitLoop(threadId, loopId, codeLocation)

        if (!isReachableFromOutsideLoop || match != null) {
            val enterCodeLocation = match?.second?.key?.codeLocation ?: codeLocation
            match?.let { (frame, loop) ->
                loop.iterationCount = 0
                frame.loops.remove(loop)
            }
            return enterCodeLocation
        }
        return null
    }

    override fun getCurrentLoopIteration(threadId: Int, loopId: Int, codeLocation: Int): Int {
        val state = state(threadId)
        val frame = state.callStack.lastOrNull() ?: return 1
        val loop = frame.loops.lastOrNull { it.key.loopId == loopId && it.key.codeLocation == codeLocation } ?: return 1
        return loop.iterationCount
    }

    // --- Method calls  ---

    override fun onMethodEnter(
        threadId: Int,
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ): LoopDetector.Decision {
        val state = state(threadId)
        val stack = state.callStack
        val top = stack.lastOrNull()

        val counters = state.methodCallCounters
        val methodCallCounter = (counters[methodId] ?: 0) + 1
        counters[methodId] = methodCallCounter
        if (methodCallCounter > recursiveCallsBound) {
            return LoopDetector.Decision.STUCK
        }

        if (top != null && top.methodId == methodId) {
            top.depth += 1
            if (top.depth > recursiveCallsBound) {
                return LoopDetector.Decision.STUCK
            }
        } else {
            val newFrame = ActiveMethodCallInfo(methodId = methodId)
            stack.addLast(newFrame)
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
        val state = state(threadId)
        val stack = state.callStack
        val top = stack.lastOrNull() ?: return

        val counters = state.methodCallCounters
        val methodCallCounter = (counters[methodId] ?: 1) - 1
        if (methodCallCounter <= 0) counters.remove(methodId) else counters[methodId] = methodCallCounter

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
}
