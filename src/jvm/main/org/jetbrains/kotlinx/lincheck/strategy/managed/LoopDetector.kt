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

import org.jetbrains.kotlinx.lincheck.util.mutableThreadMapOf

interface LoopDetector {
    enum class Decision {
        IDLE,
        SWITCH_THREAD, // N iterations
        STUCK // M iterations
    }

    fun resetAll()              // between iterations
    fun resetThread(threadId: Int) // when a thread finishes

    fun beforeLoopEnter(threadId: Int, codeLocation: Int, loopId: Int)
    fun onLoopIteration(threadId: Int, codeLocation: Int, loopId: Int): Pair<Boolean, Decision>
    fun onIrreducibleLoopIteration(threadId: Int, codeLocation: Int, loopId: Int): Decision
    fun afterLoopExit(threadId: Int, codeLocation: Int, loopId: Int, isReachableFromOutsideLoop: Boolean): Int?

    // TODO: at the moment params are only passed, but not used
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

    fun getCurrentIteration(threadId: Int, loopId: Int, codeLocation: Int) : Int

    fun currentMethodId(threadId: Int): Int
    fun isLoopInStack(threadId: Int, loopId: Int) : Boolean

//  TODO: Implement in next iteration, find where to call (beforeWriteField, beforeWriteArrayElement, afterLocalWrite)
//    fun onStageChange(threadDescriptor: ThreadDescriptor, codeLocation: Int, variableId: Int, valueHash: Int)
}

private data class LoopKey(
    val loopId: Int,
    val codeLocation: Int,
)

private data class ActiveLoopInfo (
    val key: LoopKey,
    var iterationCount: Int = 0,
)

private data class ActiveMethodCallInfo(
    val methodId: Int,
    var depth: Int = 1,
    val loops: ArrayDeque<ActiveLoopInfo> = ArrayDeque(),
)

private data class LoopDetectorThreadState(
    val threadId: Int,
    val callStack: ArrayDeque<ActiveMethodCallInfo> = ArrayDeque(),
    val methodCallCounters: MutableMap<Int /* MethodId */, Int> = mutableMapOf()
)

class BoundedLoopDetector(
    val iterationsBeforeThreadSwitch: Int, // N limit
    val iterationsBound: Int, // M limit
    val recursiveCallsBound: Int, // limit for recursive calls
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

    override fun currentMethodId(threadId: Int): Int =
        currentFrame(threadId)?.methodId ?: -1

    override fun isLoopInStack(threadId: Int, loopId: Int): Boolean {
        val state = threadStates[threadId] ?: return false
        return state.callStack.any { frame ->
            frame.loops.any { it.key.loopId == loopId }
        }
    }

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

    // --- LOOP LEVEL ---
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

    // --- IRREDUCIBLE LOOPS ---
    override fun onIrreducibleLoopIteration(threadId: Int, codeLocation: Int, loopId: Int): LoopDetector.Decision {
        val state = threadStates[threadId] ?: return LoopDetector.Decision.IDLE
        val frame = state.callStack.lastOrNull() ?: return LoopDetector.Decision.IDLE

        val loop = frame.loops.lastOrNull { it.key.loopId == loopId && it.key.codeLocation == codeLocation }
            ?: ActiveLoopInfo(LoopKey(loopId, codeLocation)).also { frame.loops.addLast(it) }

        return computeLoopDecision(loop)
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
        val currentMethod = currentMethodId(threadId)

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

    // --- METHOD LEVEL ---
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

    override fun getCurrentIteration(threadId: Int, loopId: Int, codeLocation: Int): Int {
        val state = state(threadId)
        val stack = state.callStack
        val frame = stack.lastOrNull()?: return 1
        val loop = frame.loops.lastOrNull { it.key.loopId == loopId && it.key.codeLocation == codeLocation } ?: return 1

        return loop.iterationCount
    }
}