/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel10

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class Producer {
    suspend fun produce(channel: Channel<Int>) {
        channel.send(1)  // Sends value 1 to the channel
    }
}

class Consumer {
    suspend fun consume(channel: Channel<Int>) {
        channel.receive()  // Tries to receive a value from the channel
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val channel = Channel<Int>()
    val producer = Producer()
    val consumer = Consumer()

    // Coroutine 1: Producer sends a value to the channel
    launch(dispatcher) {
        producer.produce(channel)
    }

    // Coroutine 2: Consumer tries to receive a value from the channel
    launch(dispatcher) {
        consumer.consume(channel)
    }
}

class ChannelTest10 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}