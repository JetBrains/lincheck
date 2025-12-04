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

interface LoopDetector {

    fun beforeLoopEnter(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int)
    fun onLoopIteration(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int)
    fun afterLoopExit(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int)

    fun onMethodEnter(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
    )

    fun onMethodExit(
        threadDescriptor: ThreadDescriptor,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?,
    )
}

class BoundedLoopDetector(
    val iterationsBeforeThreadSwitch: Int,
    val iterationsBound: Int,
    val recursiveCallsBound: Int,
) : LoopDetector {

    // Maintain the following state:
    //    threadId -> Stack<ActiveMethodCall>
    //
    //    ActiveMethodCall := (methodId, params?, Stack<ActiveLoop>)
    //    ActiveLoop := (loopId, iterations)

    // Questions: what methods we need to add to the interface to connect it with scheduling?
    // shouldSwitch(): Boolean ???

    override fun beforeLoopEnter(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int) {
        TODO("Not yet implemented")
    }

    override fun onLoopIteration(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int) {
        TODO("Not yet implemented")
    }

    override fun afterLoopExit(threadDescriptor: ThreadDescriptor, codeLocation: Int, loopId: Int) {
        TODO("Not yet implemented")
    }

    override fun onMethodEnter(
        threadDescriptor: ThreadDescriptor,
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ) {
        TODO("Not yet implemented")
    }

    override fun onMethodExit(
        threadDescriptor: ThreadDescriptor,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?
    ) {
        TODO("Not yet implemented")
    }
}