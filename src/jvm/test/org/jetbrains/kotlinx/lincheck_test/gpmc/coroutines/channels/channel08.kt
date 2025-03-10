/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel08

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class SimpleClass(val channel: Channel<Int>) {
    suspend fun sendValue(value: Int) {
        channel.send(value)
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val channel = Channel<Int>()

    val simpleClass = SimpleClass(channel)

    launch(dispatcher) {
        simpleClass.sendValue(1)
    }

    launch(dispatcher) {
        delay(100)
        channel.receive()
    }

    launch(dispatcher) {
        val received = channel.receive()
        check(received == 1)
    }
}

class ChannelTest08 : BaseChannelTest(true) {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}