/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.gpmc.coroutines.channels.channel03

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

@OptIn(DelicateCoroutinesApi::class)
fun producerA(channelA: Channel<Int>, dispatcher: CoroutineDispatcher) = GlobalScope.launch(dispatcher) {
    repeat(5) {
        channelA.send(it)
    }
    channelA.close()
}

@OptIn(DelicateCoroutinesApi::class)
fun producerB(
    channelB: Channel<Int>,
    channelA: Channel<Int>,
    dispatcher: CoroutineDispatcher
) = GlobalScope.launch(dispatcher) {
    repeat(5) {
        val value = channelA.receive() * 2
        channelB.send(value)
    }
    channelB.close()
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val channelA = Channel<Int>()
    val channelB = Channel<Int>()

    producerA(channelA, dispatcher)
    producerB(channelB, channelA, dispatcher)

    repeat(5) {
        launch(dispatcher) {
            check(channelB.receive() % 2 == 0)
        }
    }
}

class ChannelTest03 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}