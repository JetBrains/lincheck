/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel01

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class ChannelHandler {
    val channel1 = Channel<Int>()
    val channel2 = Channel<Int>()

    suspend fun sendToChannel1() {
        channel1.send(1)
        receiveFromChannel2()
    }

    suspend fun receiveFromChannel2() {
        check(channel2.receive() == 2)
    }

    suspend fun sendToChannel2() {
        channel2.send(2)
        receiveFromChannel1()
    }

    suspend fun receiveFromChannel1() {
        check(channel1.receive() == 1)
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val handler = ChannelHandler()

    launch(dispatcher) {
        handler.sendToChannel1()
    }
    handler.sendToChannel2()
}

class ChannelTest01 : BaseChannelTest(true) {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) {
            main(dispatcher)
        }
    }
}