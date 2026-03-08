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

class AdaptiveLoopDetector() : LoopDetector   {

    override fun resetAll() {
        TODO("Not yet implemented")
    }

    override fun resetThread(threadId: Int) {
        TODO("Not yet implemented")
    }

    override fun beforeLoopEnter(threadId: Int, codeLocation: Int, loopId: Int) {
        TODO("Not yet implemented")
    }

    override fun onLoopIteration(
        threadId: Int,
        codeLocation: Int,
        loopId: Int
    ): Pair<Boolean, LoopDetector.Decision> {
        TODO("Not yet implemented")
    }

    override fun afterLoopExit(
        threadId: Int,
        codeLocation: Int,
        loopId: Int,
        isReachableFromOutsideLoop: Boolean
    ): Int? {
        TODO("Not yet implemented")
    }

    override fun onMethodEnter(
        threadId: Int,
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ): LoopDetector.Decision {
        TODO("Not yet implemented")
    }

    override fun onMethodExit(
        threadId: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?
    ) {
        TODO("Not yet implemented")
    }

    override fun onIrreducibleLoop(
        threadId: Int,
        codeLocation: Int
    ): LoopDetector.Decision {
        TODO("Not yet implemented")
    }

    override fun onAwaitLoop(
        threadId: Int,
        codeLocation: Int,
        loopId: Int
    ): Pair<Boolean, LoopDetector.Decision> {
        TODO("Not yet implemented")
    }

    override fun getCurrentIteration(threadId: Int, loopId: Int, codeLocation: Int): Int {
        TODO("Not yet implemented")
    }

    override fun currentMethodId(threadId: Int): Int {
        TODO("Not yet implemented")
    }

    override fun loopIsInStack(threadId: Int, loopId: Int): Boolean {
        TODO("Not yet implemented")
    }
}