/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.gpmc.coroutines.channels.channel07

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class ExampleClass {
    val channel1 = Channel<Int>()
    val channel2 = Channel<String>()
    val channel3 = Channel<Double>()
    val channel4 = Channel<Long>()
    
    suspend fun producer1() {
        channel1.send(1)
        channel1.send(2)
    }

    suspend fun producer2() {
        channel2.send("Hello")
        channel2.send("World")
    }

    suspend fun consumer1() {
        channel1.receive()
        channel2.receive()
    }

    suspend fun consumer2() {
        channel3.receive()
        channel4.receive()
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val example = ExampleClass()

    launch(dispatcher) {
        example.producer1()
    }
    
    launch(dispatcher) {
        example.producer2()
    }
    
    launch(dispatcher) {
        example.consumer1()
        example.consumer1()
    }
    
    launch(dispatcher) {
        example.consumer2()
    }

    example.channel3.send(3.14)
    example.channel4.send(123456789L)
}

class ChannelTest07 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}