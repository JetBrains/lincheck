/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.gpmc.coroutines.channels.channel19

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class Worker {
    suspend fun process(ch1: Channel<Int>, ch2: Channel<Int>) {
        for (i in 1..5) {
            val received = ch1.receive()
            check(received == i)
            ch2.send(received * 2)
        }
    }
}

suspend fun sender(ch: Channel<Int>) {
    repeat(5) {
        ch.send(it + 1)
    }
}

suspend fun receiver(ch: Channel<Int>) {
    repeat(5) {
        check(ch.receive() == (it + 1) * 2)
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val ch1 = Channel<Int>()
    val ch2 = Channel<Int>()
    val worker = Worker()

    launch(dispatcher) { sender(ch1) }
    launch(dispatcher) { worker.process(ch1, ch2) }
    launch(dispatcher) { receiver(ch2) }
}

class ChannelTest19 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}