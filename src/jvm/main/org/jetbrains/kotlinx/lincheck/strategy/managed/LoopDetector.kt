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
    fun onLoopIteration(threadId: Int, codeLocation: Int, loopId: Int, methodId: Int): Decision
    fun afterLoopExit(threadId: Int, codeLocation: Int, loopId: Int, methodId: Int)

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

//  TODO: Implement in next iteration, find where to call (beforeWriteField, beforeWriteArrayElement, afterLocalWrite)
//    fun onStageChange(threadDescriptor: ThreadDescriptor, codeLocation: Int, variableId: Int, valueHash: Int)
}

data class ActiveLoopInfo (
    val loopId: Int,
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

    // --- LOOP LEVEL ---
    override fun beforeLoopEnter(threadId: Int, codeLocation: Int, loopId: Int) {
        val st = state(threadId)
        val stack = st.callStack

        val frame = stack.lastOrNull()?: ActiveMethodCallInfo(methodId = -1).also {
            stack.addLast(it)
        }

        frame.loops.addLast(ActiveLoopInfo(loopId))
    }

    override fun onLoopIteration(threadId: Int, codeLocation: Int, loopId: Int, methodId: Int): LoopDetector.Decision {
        val st = state(threadId)
        val stack = st.callStack
        val frame = stack.lastOrNull()?: return LoopDetector.Decision.IDLE

        if (frame.methodId != methodId && frame.methodId != -1) {
            // something is wrong
            return LoopDetector.Decision.IDLE
        }

        val loop = frame.loops.lastOrNull {it.loopId == loopId} ?: ActiveLoopInfo(loopId).also {
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

    override fun afterLoopExit(threadId: Int, codeLocation: Int, loopId: Int, methodId: Int) {
        val st = state(threadId)
        val stack = st.callStack
        val frame = stack.lastOrNull()?: return

        val loop = frame.loops.lastOrNull {it.loopId == loopId} ?: return
        if (frame.loops.lastOrNull() === loop) {
            frame.loops.removeLast()
        } else {
            val idx = frame.loops.indexOfLast {it.loopId == loopId}
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

        if (top.methodId != methodId) {
            // something is wrong
            return
        }

        if (top.depth > 1) {
            top.depth -= 1
        } else {
            // no more recursion levels
            stack.removeLast()
        }
    }
}