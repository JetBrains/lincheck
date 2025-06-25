/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel17

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

suspend fun coroutine1(ch1: Channel<Int>, ch2: Channel<Int>) {
    ch1.send(1)
    ch2.receive()
}

suspend fun coroutine2(ch2: Channel<Int>, ch3: Channel<Int>) {
    ch3.send(2)
    ch2.receive()
}

suspend fun coroutine3(ch3: Channel<Int>, ch4: Channel<Int>) {
    ch4.send(3)
    ch3.receive()
}

suspend fun coroutine4(ch5: Channel<Int>, ch1: Channel<Int>) {
    ch1.receive()
    ch5.send(4)
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val channel1 = Channel<Int>()
    val channel2 = Channel<Int>()
    val channel3 = Channel<Int>()
    val channel4 = Channel<Int>()
    val channel5 = Channel<Int>()

    launch(dispatcher) { coroutine1(channel1, channel2) }
    launch(dispatcher) { coroutine2(channel2, channel3) }
    launch(dispatcher) { coroutine3(channel3, channel4) }
    launch(dispatcher) { coroutine4(channel5, channel1) }
}

class ChannelTest17 : BaseChannelTest(true) {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}