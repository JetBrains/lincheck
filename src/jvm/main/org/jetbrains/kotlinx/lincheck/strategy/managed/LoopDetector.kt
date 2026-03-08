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

    fun onIrreducibleLoop(threadId: Int, codeLocation: Int): Decision
    fun onAwaitLoop(threadId: Int, codeLocation: Int, loopId: Int): Pair<Boolean, Decision>

    fun getCurrentIteration(threadId: Int, loopId: Int, codeLocation: Int) : Int

    fun currentMethodId(threadId: Int): Int
    fun loopIsInStack(threadId: Int, loopId: Int) : Boolean

//  TODO: Implement in next iteration, find where to call (beforeWriteField, beforeWriteArrayElement, afterLocalWrite)
//    fun onStageChange(threadDescriptor: ThreadDescriptor, codeLocation: Int, variableId: Int, valueHash: Int)
}

internal data class LoopKey(
    val loopId: Int,
    val codeLocation: Int,
)

internal data class ActiveLoopInfo (
    val key: LoopKey,
    var iterationCount: Int = 0,
)

internal data class LoopContext (
    val loopId: Int,
    val methodId: Int,
    val codeLocation: Int,
)

internal data class ActiveMethodCallInfo(
    val methodId: Int,
    var depth: Int = 1,
    val loops: ArrayDeque<ActiveLoopInfo> = ArrayDeque(),
)

internal data class LoopDetectorThreadState(
    val threadId: Int,
    val callStack: ArrayDeque<ActiveMethodCallInfo> = ArrayDeque(),
    val methodCallCounters: MutableMap<Int /* MethodId */, Int> = mutableMapOf()
)
