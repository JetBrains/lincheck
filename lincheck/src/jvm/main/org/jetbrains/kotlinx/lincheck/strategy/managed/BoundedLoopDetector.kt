package org.jetbrains.kotlinx.lincheck.strategy.managed

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
    override val recursiveCallsBound: Int,  // K limit for recursive calls before stuck
) : AbstractLoopDetector() {
    // --- Loops ---

    override fun onLoopIteration(
        threadId: Int,
        codeLocation: Int,
        loopId: Int
    ): Pair<Boolean, LoopDetector.Decision> {
        val (loop, started) = startLoopIfNeeded(threadId, codeLocation, loopId)
        val decision = computeLoopDecision(loop)
        return Pair(started, decision)
    }

    override fun onIrreducibleLoopIteration(threadId: Int, codeLocation: Int, loopId: Int): LoopDetector.Decision {
        val state = threadStates[threadId] ?: return LoopDetector.Decision.IDLE
        val frame = state.callStack.lastOrNull() ?: return LoopDetector.Decision.IDLE

        val loop = frame.loops.lastOrNull { it.key.loopId == loopId && it.key.codeLocation == codeLocation }
            ?: ActiveLoopInfo(LoopKey(loopId, codeLocation)).also { frame.loops.addLast(it) }

        return computeLoopDecision(loop)
    }

    private fun computeLoopDecision(loop: ActiveLoopInfo): LoopDetector.Decision {
        loop.iterationCount += 1

        if (loop.iterationCount % iterationsBeforeThreadSwitch == 0)
            return LoopDetector.Decision.SWITCH_THREAD

        if (loop.iterationCount >= iterationsBound)
            return LoopDetector.Decision.STUCK

        return LoopDetector.Decision.IDLE
    }
}