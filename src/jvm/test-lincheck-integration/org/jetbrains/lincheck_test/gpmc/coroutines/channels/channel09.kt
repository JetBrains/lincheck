/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel09

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class ProcessorA(val input: Channel<Int>, val output: Channel<Int>) {
    suspend fun process() {
        val data = input.receive()
        output.send(data * 2)
    }
}

class ProcessorB(val input: Channel<Int>, val output: Channel<Int>) {
    suspend fun process() {
        val data = input.receive()
        output.send(data + 3)
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val channel1 = Channel<Int>()
    val channel2 = Channel<Int>()
    val channel3 = Channel<Int>()

    val processorA = ProcessorA(channel1, channel2)
    val processorB = ProcessorB(channel2, channel3)

    launch(dispatcher) {
        processorA.process()
    }

    launch(dispatcher) {
        channel1.send(1)
    }

    launch(dispatcher) {
        processorB.process()
    }

    launch(dispatcher) {
        val result = channel3.receive()
        check(result == 5)
    }
}

class ChannelTest09 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}