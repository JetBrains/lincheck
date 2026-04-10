package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.util.mutableThreadMapOf
import kotlin.collections.lastOrNull
import kotlin.math.abs

/**
 * A [LoopDetector] implementation that uses bounded iteration and call-depth counters to detect livelocks.
 *
 * For each thread, the detector maintains a call stack of active method frames,
 * each of which tracks a stack of active loops within that method.
 *
 * **Loop detection:**
 * on each loop iteration, an iteration counter is incremented.
 * When the counter reaches a multiple of [iterationsBeforeThreadSwitch],
 * a [LoopDetector.Decision.SWITCH_THREAD] decision is produced to give other threads a chance to make progress.
 * If the counter reaches [iterationsBound], a [LoopDetector.Decision.STUCK] decision is produced,
 * indicating a suspected livelock.
 *
 * **Recursion detection:**
 * on each method entry, per-method call counters are incremented.
 * If either the number of recursive calls of a method exceeds [recursiveCallsBound],
 * a [LoopDetector.Decision.STUCK] decision is produced.
 *
 * @param iterationsBeforeThreadSwitch the number of loop iterations
 *   after which a thread context switch is suggested.
 * @param iterationsBound the upper bound on loop iterations;
 *   exceeding this limit is treated as a livelock.
 * @param recursiveCallsBound the upper bound on recursive method call depth;
 *   exceeding this limit is treated as a livelock.
 */
class BoundedLoopDetector(
    val iterationsBeforeThreadSwitch: Int,  // N limit for loop iterations before thread switch
    val iterationsBound: Int,               // M limit for loop iterations before stuck
    val recursiveCallsBound: Int,           // K limit for recursive calls before stuck
) : LoopDetector {
    private val threadStates = mutableThreadMapOf<LoopDetectorThreadState>()

    override fun resetAll() {
        threadStates.clear()
    }

    override fun resetThread(threadId: Int) {
        threadStates.remove(threadId)
    }

    private fun state(threadId: Int): LoopDetectorThreadState =
        threadStates.getOrPut(threadId) {
            LoopDetectorThreadState(threadId)
        }

    private fun currentFrame(threadId: Int): ActiveMethodCallInfo? =
        threadStates[threadId]?.callStack?.lastOrNull()

    private fun getOrCreateFrame(threadId: Int): ActiveMethodCallInfo {
        val state = state(threadId)
        return state.callStack.lastOrNull() ?: ActiveMethodCallInfo(methodId = -1).also {
            state.callStack.addLast(it)
        }
    }

    override fun getCurrentMethodId(threadId: Int): Int =
        currentFrame(threadId)?.methodId ?: -1

    private fun findLoopInCurrentFrame(
        threadId: Int,
        loopId: Int,
        codeLocation: Int
    ): ActiveLoopInfo? {
        val frame = currentFrame(threadId) ?: return null
        return frame.loops.lastOrNull {
            it.key.loopId == loopId && it.key.codeLocation == codeLocation
        }
    }

    // --- Loops ---

    override fun onLoopIteration(
        threadId: Int,
        codeLocation: Int,
        loopId: Int
    ): Pair<Boolean, LoopDetector.Decision> {
        var loop = findLoopInCurrentFrame(threadId, loopId, codeLocation)

        val started = if (loop == null) {
            beforeLoopEnter(threadId, codeLocation, loopId)
            loop = findLoopInCurrentFrame(threadId, loopId, codeLocation)
            true
        } else {
            false
        }
        val decision = computeLoopDecision(loop!!)
        return Pair(started, decision)
    }

    override fun onIrreducibleLoopIteration(threadId: Int, codeLocation: Int, loopId: Int): LoopDetector.Decision {
        val state = threadStates[threadId] ?: return LoopDetector.Decision.IDLE
        val frame = state.callStack.lastOrNull() ?: return LoopDetector.Decision.IDLE

        val loop = frame.loops.lastOrNull { it.key.loopId == loopId && it.key.codeLocation == codeLocation }
            ?: ActiveLoopInfo(LoopKey(loopId, codeLocation)).also { frame.loops.addLast(it) }

        return computeLoopDecision(loop)
    }

    override fun onAwaitLoopIteration(threadId: Int, codeLocation: Int, loopId: Int): Pair<Boolean, LoopDetector.Decision> {
        return onLoopIteration(threadId, codeLocation, loopId)
    }

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

    private fun findExitLoop(
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

    private fun computeLoopDecision(loop: ActiveLoopInfo): LoopDetector.Decision {
        loop.iterationCount += 1

        if (loop.iterationCount % iterationsBeforeThreadSwitch == 0)
            return LoopDetector.Decision.SWITCH_THREAD

        if (loop.iterationCount >= iterationsBound)
            return LoopDetector.Decision.STUCK

        return LoopDetector.Decision.IDLE
    }

    override fun getCurrentLoopIteration(threadId: Int, loopId: Int, codeLocation: Int): Int {
        val state = state(threadId)
        val stack = state.callStack
        val frame = stack.lastOrNull()?: return 1
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
    ) : LoopDetector.Decision {
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
                // something is wrong - too deep recursion
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

        // If methodId is not on top, then we need to find it in the stack and remove all frames above it.
        val idx = stack.indexOfLast { it.methodId == methodId }
        if (idx == -1) return
        while (stack.size - 1 > idx) stack.removeLast()
        val frame = stack.lastOrNull() ?: return
        if (frame.depth > 1) frame.depth -= 1 else stack.removeLast()
    }
}