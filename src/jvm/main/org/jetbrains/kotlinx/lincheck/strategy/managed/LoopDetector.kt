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

import sun.nio.ch.lincheck.ThreadDescriptor
import java.util.IdentityHashMap

interface LoopDetector {
    enum class Decision {
        IDLE,
        SWITCH_THREAD, //n iterations
        STUCK // m iterations
    }
    fun beforeLoopEnter(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int)
    fun onLoopIteration(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int, methodId: Int): Decision
    fun afterLoopExit(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int, methodId: Int)

    fun onMethodEnter(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
    ) : Decision

    fun onMethodExit(
        threadDescriptor: ThreadDescriptor,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?,
    )

    // TODO But, where do we call this???? beforeWriteField, beforeWriteArrayElement, afterLocalWrite (which is empty)??????
    fun onStageChange(threadDescriptor: ThreadDescriptor, codeLocation: Int, variableId: Int, valueHash: Int)
}

data class ActiveLoop (
    val loopId: Int,
    var iterationCount: Int = 0,
    var switched: Boolean = false,
    var stuck: Boolean = false,
    var lastSignature: Int = 0,
    var progressSignature: Int = 1,
)

data class ActiveMethodCall (
    val methodId: Int,
    var depth: Int = 1,
    val params: Array<Any?>? = null,
    val loops: ArrayDeque<ActiveLoop> = ArrayDeque(),
)

data class LoopThreadState (
    val threadId: Int,
    val callStack: ArrayDeque<ActiveMethodCall> = ArrayDeque(),
)

class BoundedLoopDetector(
    val iterationsBeforeThreadSwitch: Int, //n
    val iterationsBound: Int, //m
    val recursiveCallsBound: Int,
) : LoopDetector {

    // Maintain the following state:
    //    threadId -> Stack<ActiveMethodCall>
    //
    //    ActiveMethodCall := (methodId, params?, Stack<ActiveLoop>)
    //    ActiveLoop := (loopId, iterations)

    // Questions: what methods we need to add to the interface to connect it with scheduling?
    // shouldSwitch(): Boolean ???

    private val threadStates = IdentityHashMap<ThreadDescriptor, LoopThreadState>()

    private fun state(threadDescriptor: ThreadDescriptor): LoopThreadState =
        threadStates.getOrPut(threadDescriptor) {
            LoopThreadState(threadDescriptor.thread.id.toInt())
        }

    // TODO in work
    override fun onStageChange(threadDescriptor: ThreadDescriptor, codeLocation: Int, variableId: Int, valueHash: Int) {
        val st = state(threadDescriptor)
        val stack = st.callStack
        val frame = stack.lastOrNull()?: return

        // Works for loops i guess.  But it should also work for recursive
        // TODO can this approach support recursion tho?
        val loop = frame.loops.lastOrNull() ?: return

        loop.progressSignature = loop.progressSignature * 31 + variableId * 17 + valueHash
    }

    // --- LOOP LEVEL ---
    override fun beforeLoopEnter(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int) {
        val st = state(threadDescriptor)
        val stack = st.callStack

        val frame = stack.lastOrNull()?: ActiveMethodCall(methodId = -1).also {
            stack.addLast(it)
        }

        frame.loops.addLast(ActiveLoop(loopId))
    }

    override fun onLoopIteration(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int, methodId: Int): LoopDetector.Decision {
        val st = state(threadDescriptor)
        val stack = st.callStack
        val frame = stack.lastOrNull()?: return LoopDetector.Decision.IDLE

        if (frame.methodId != methodId && frame.methodId != -1) {
            // something is wrong
            return LoopDetector.Decision.IDLE
        }

        val loop = frame.loops.lastOrNull() {it.loopId == loopId} ?: ActiveLoop(loopId).also {
            frame.loops.addLast(it)
        }

        // check progress on the stack
        val prevSig = loop.lastSignature
        val currSig = loop.progressSignature

        if (loop.iterationCount > 0 && prevSig != currSig) {
            // progress detected
            loop.iterationCount = 0
            loop.switched = false
            loop.lastSignature = currSig
            return LoopDetector.Decision.IDLE
        } else if (loop.iterationCount == 0) {
            loop.lastSignature = currSig
        }

        loop.iterationCount += 1
        loop.progressSignature = 1

        if (loop.stuck) return LoopDetector.Decision.STUCK

        if (loop.iterationCount >= iterationsBound) {
            loop.stuck = true
            return LoopDetector.Decision.STUCK
        }

        if (!loop.switched && loop.iterationCount >= iterationsBeforeThreadSwitch) {
            loop.switched = true
            loop.iterationCount = 0
            return LoopDetector.Decision.SWITCH_THREAD
        }

        return LoopDetector.Decision.IDLE

    }

    override fun afterLoopExit(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int, methodId: Int) {
        val st = state(threadDescriptor)
        val stack = st.callStack
        val frame = stack.lastOrNull()?: return

        val loop = frame.loops.lastOrNull() {it.loopId == loopId} ?: return
        if (frame.loops.lastOrNull() === loop) {
            frame.loops.removeLast()
        } else {
            //
            val idx = frame.loops.indexOfLast {it.loopId == loopId}
            if (idx != -1) {
                frame.loops.removeAt(idx)
            }
        }
    }

    // --- METHOD LEVEL ---
    override fun onMethodEnter(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ) : LoopDetector.Decision {
        val st = state(threadDescriptor)
        val stack = st.callStack
        val top = stack.lastOrNull()

        if (top != null && top.methodId == methodId) {
            top.depth += 1
            if (top.depth > recursiveCallsBound) {
                // something is wrong - too deep recursion
                return LoopDetector.Decision.STUCK
            }
        } else {
            val newFrame = ActiveMethodCall(methodId = methodId, params = params)
            stack.addLast(newFrame)
        }

        return LoopDetector.Decision.IDLE
    }

    override fun onMethodExit(
        threadDescriptor: ThreadDescriptor,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?
    ) {
        val st = state(threadDescriptor)
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