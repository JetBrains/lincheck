/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.gpmc.coroutines.channels.channel20

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class Example {
    val inputChannel = Channel<Int>()
    val outputChannel = Channel<Int>()
    val processingChannel1 = Channel<Int>()
    val processingChannel2 = Channel<Int>()
}

suspend fun processInput(example: Example, dispatcher: CoroutineDispatcher) = coroutineScope {
    launch(dispatcher) {
        for (i in example.inputChannel) {
            example.processingChannel1.send(i * 10)
        }
        example.processingChannel1.close()
    }

    launch(dispatcher) {
        for (i in example.processingChannel1) {
            example.processingChannel2.send(i + 1)
        }
        example.processingChannel2.close()
    }

    launch(dispatcher) {
        for (i in example.processingChannel2) {
            example.outputChannel.send(i * 2)
        }
        example.outputChannel.close()
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val example = Example()

    launch(dispatcher) {
        for (i in 1..5) {
            example.inputChannel.send(i)
        }
        example.inputChannel.close()
    }

    launch(dispatcher) {
        processInput(example, dispatcher)
    }

    repeat(5) {
        val res = example.outputChannel.receive()
        check(res == ((it + 1) * 10 + 1) * 2)
    }
}

class ChannelTest20 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}