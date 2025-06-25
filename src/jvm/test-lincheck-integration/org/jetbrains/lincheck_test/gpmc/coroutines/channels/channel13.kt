/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel13

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class FooBar(private val dispatcher: CoroutineDispatcher) {
    private val channel1 = Channel<Int>()
    private val channel2 = Channel<Int>()
    private val channel3 = Channel<Int>()

    suspend fun foo() {
        val x = channel2.receive()
        channel1.send(x)
    }

    suspend fun bar() {
        val y = channel1.receive()
        channel3.send(y)
    }

    suspend fun baz() {
        val z = channel3.receive()
        channel2.send(z)
    }

    fun launchCoroutines() {
        runBlocking(dispatcher) {
            launch(dispatcher) { alpha() }
            launch(dispatcher) { beta() }
            launch(dispatcher) { gamma() }
            launch(dispatcher) { delta() }
            launch(dispatcher) { epsilon() }
        }
    }

    private suspend fun alpha() {
        channel1.send(1)
    }

    private suspend fun beta() {
        foo()
    }

    private suspend fun gamma() {
        bar()
    }

    private suspend fun delta() {
        baz()
    }

    private suspend fun epsilon() {
        channel3.send(2)
    }
}

fun main(dispatcher: CoroutineDispatcher) {
    FooBar(dispatcher).launchCoroutines()
}

class ChannelTest13 : BaseChannelTest(true) {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}