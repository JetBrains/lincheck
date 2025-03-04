/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel16

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class MessageSender(private val channel: Channel<Int>) {
    suspend fun sendMessage(message: Int) {
        channel.send(message)
    }
}

fun CoroutineScope.setupCoroutines(channel: Channel<Int>, dispatcher: CoroutineDispatcher) {
    launch(dispatcher) {
        channel.receive()
    }
    launch(dispatcher) {
        channel.receive()
    }
    launch(dispatcher) {
        channel.receive()
    }
    launch(dispatcher) {
        channel.receive()
    }
    launch(dispatcher) {
        channel.receive()
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val channel = Channel<Int>()
    val messageSender = MessageSender(channel)
    
    setupCoroutines(channel, dispatcher)
    
    messageSender.sendMessage(1)
    messageSender.sendMessage(2)
    messageSender.sendMessage(3)
    messageSender.sendMessage(4)
    messageSender.sendMessage(5)
}

class ChannelTest16 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}