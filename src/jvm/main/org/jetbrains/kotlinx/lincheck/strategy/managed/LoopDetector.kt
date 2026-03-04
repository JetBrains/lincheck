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
    fun loopIsInStack(threadId: Int, loopId: Int) : Boolean

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

private data class LoopContext (
    val loopId: Int,
    val methodId: Int,
    val codeLocation: Int,
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

    // Maintain the following state:
    //    threadId -> Stack<ActiveMethodCall>
    //
    //    ActiveMethodCall := (methodId, params?, Stack<ActiveLoop>)
    //    ActiveLoop := (loopId, iterations)

    // Questions: what methods we need to add to the interface to connect it with scheduling?
    // shouldSwitch(): Boolean ???

    private val threadStates = mutableThreadMapOf<LoopDetectorThreadState>()
    // Per-thread stack of currently active loops
    private val activeLoopStack = mutableThreadMapOf<ArrayDeque<LoopContext>>()

    private fun loopStack(thread: Int): ArrayDeque<LoopContext> =
        activeLoopStack.getOrPut(thread) { ArrayDeque() }

    override fun resetAll() {
        threadStates.clear()
        activeLoopStack.clear()
    }

    override fun resetThread(threadId: Int) {
        threadStates.remove(threadId)
        activeLoopStack.remove(threadId)
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

    // --- LOOP LEVEL ---
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
        val decision = computeLoopDecision(threadId, codeLocation, loopId, methodId)
        return Pair(started, decision)
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
                .filter{ it.value.loopId == loopId && it.value.methodId == methodId }
                .minByOrNull { kotlin.math.abs(it.value.codeLocation - codeLocation) }
                ?.index
            val loop = if (indexToRemove != null) {
                val temp = ArrayDeque<LoopContext>()
                while (stack.size - 1 > indexToRemove) temp.addFirst(stack.removeLast())
                val found = stack.removeLast()
                for (l in temp) stack.addLast(l)
                found
            } else null

            val enterCodeLocation = loop?.codeLocation ?: codeLocation
            removeLoopFromStack(threadId, loopId,)
            return enterCodeLocation
        }
        return null
    }

    override fun beforeLoopEnter(threadId: Int, codeLocation: Int, loopId: Int) {
        val st = state(threadId)
        val stack = st.callStack

        val frame = stack.lastOrNull()?: ActiveMethodCallInfo(methodId = -1).also {
            stack.addLast(it)
        }

        frame.loops.addLast(ActiveLoopInfo(LoopKey(loopId, codeLocation)))
    }

    private fun computeLoopDecision(threadId: Int, codeLocation: Int, loopId: Int, methodId: Int): LoopDetector.Decision {
        val st = state(threadId)
        val stack = st.callStack
        val frame = stack.lastOrNull()?: return LoopDetector.Decision.IDLE

        if (frame.methodId != methodId && frame.methodId != -1) {
            // something is wrong
            return LoopDetector.Decision.IDLE
        }

        val loop = frame.loops.lastOrNull { it.key.loopId == loopId && it.key.codeLocation == codeLocation } ?: ActiveLoopInfo(LoopKey(loopId, codeLocation)).also {
            frame.loops.addLast(it)
        }

        // TODO check if progress is being made here, before incrementing iterationCount. For next iteration.
        
        loop.iterationCount += 1

        if (loop.iterationCount % iterationsBeforeThreadSwitch == 0)
            return LoopDetector.Decision.SWITCH_THREAD

        if (loop.iterationCount >= iterationsBound)
            return LoopDetector.Decision.STUCK

        return LoopDetector.Decision.IDLE

    }

    private fun removeLoopFromStack(threadId: Int, loopId: Int) {
        val st = state(threadId)
        val stack = st.callStack
        val frame = stack.lastOrNull()?: return

        val loop = frame.loops.lastOrNull { it.key.loopId == loopId } ?: return
        loop.iterationCount = 0 // reset iteration count as a sanity check
        if (frame.loops.lastOrNull() === loop) {
            frame.loops.removeLast()
        } else {
            val idx = frame.loops.indexOfLast { it.key.loopId == loopId }
            if (idx != -1) {
                frame.loops.removeAt(idx)
            }
        }
    }

    // --- METHOD LEVEL ---
    override fun onMethodEnter(
        threadId: Int,
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ) : LoopDetector.Decision {
        val st = state(threadId)
        val stack = st.callStack
        val top = stack.lastOrNull()

        val counters = st.methodCallCounters
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
        val st = state(threadId)
        val stack = st.callStack
        val top = stack.lastOrNull() ?: return

        val counters = st.methodCallCounters
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
        val st = state(threadId)
        val stack = st.callStack
        val frame = stack.lastOrNull()?: return 1
        val loop = frame.loops.lastOrNull { it.key.loopId == loopId && it.key.codeLocation == codeLocation } ?: return 1

        return loop.iterationCount
    }
}