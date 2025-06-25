/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel02

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class Producer1(private val outChannel: Channel<Int>) {
    suspend fun produce() {
        for (i in 1..5) {
            outChannel.send(i)
        }
        outChannel.close()
    }
}

class Producer2(private val outChannel: Channel<Int>) {
    suspend fun produce() {
        for (i in 6..10) {
            outChannel.send(i)
        }
        outChannel.close()
    }
}

class Consumer(private val inChannel1: Channel<Int>, private val inChannel2: Channel<Int>, private val outChannel: Channel<Int>) {
    suspend fun consume() {
        for (element1 in inChannel1) {
            outChannel.send(element1 * 2)
        }
        for (element2 in inChannel2) {
            outChannel.send(element2 * 2)
        }
        outChannel.close()
    }
}

suspend fun relay(from: Channel<Int>, to: Channel<Int>) {
    for (element in from) {
        to.send(element)
    }
    to.close()
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val producerChannel1 = Channel<Int>()
    val producerChannel2 = Channel<Int>()
    val consumerChannel1 = Channel<Int>()
    val consumerChannel2 = Channel<Int>()
    val resultChannel = Channel<Int>()

    val producer1 = Producer1(producerChannel1)
    val producer2 = Producer2(producerChannel2)
    val consumer = Consumer(consumerChannel1, consumerChannel2, resultChannel)

    launch(dispatcher) { producer1.produce() }
    launch(dispatcher) { producer2.produce() }
    launch(dispatcher) { relay(producerChannel1, consumerChannel1) }
    launch(dispatcher) { relay(producerChannel2, consumerChannel2) }
    launch(dispatcher) { consumer.consume() }

    for (result in resultChannel) {
        check(result % 2 == 0 && (result / 2) in 1..10)
    }
}

class ChannelTest02 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}