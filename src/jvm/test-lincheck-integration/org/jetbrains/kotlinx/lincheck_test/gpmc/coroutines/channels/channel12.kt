/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel12

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class Producer(private val channel: Channel<Int>) {
    suspend fun produce() {
        for (i in 1..3) {
            channel.send(i)
        }
    }
}

class Consumer(private val channel: Channel<Int>) {
    suspend fun consume() {
        for (i in 1..3) {
            channel.receive()
        }
    }
}

class Processor(private val channel: Channel<Int>) {
    suspend fun process() {
        channel.receive()
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val channel = Channel<Int>()

    val producer = Producer(channel)
    val consumer = Consumer(channel)
    val processor = Processor(channel)

    launch(dispatcher) { producer.produce() }
    launch(dispatcher) { consumer.consume() }
    launch(dispatcher) { processor.process() }
    launch(dispatcher) { processor.process() } // missing additional processor, thus, blocks on send in last produce
    launch(dispatcher) { producer.produce() }

    // This line will never be reached due to deadlock
}

class ChannelTest12 : BaseChannelTest(true) {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}