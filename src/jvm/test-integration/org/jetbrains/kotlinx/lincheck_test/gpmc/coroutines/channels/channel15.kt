/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel15

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class ChannelA(private val channel: Channel<Int>) {
    suspend fun produce() {
        for (i in 1..5) {
            channel.send(i)
        }
    }
}

class ChannelB(private val channel: Channel<Int>) {
    suspend fun consume(outChannel: Channel<Int>) {
        for (i in 1..5) {
            val received = channel.receive()
            outChannel.send(received * 2)
        }
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val channelA = Channel<Int>()
    val channelB = Channel<Int>()

    val producer = ChannelA(channelA)
    val consumer = ChannelB(channelA)

    launch(dispatcher) {
        producer.produce()
    }
    
    launch(dispatcher) {
        consumer.consume(channelB)
    }

    for (i in 1..5) {
        check(channelB.receive() % 2 == 0)
    }
}

class ChannelTest15 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}