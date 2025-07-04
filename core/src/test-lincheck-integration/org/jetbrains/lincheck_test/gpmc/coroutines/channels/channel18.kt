/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.gpmc.coroutines.channels.channel18

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class Processor(val channel: Channel<Int>) {
    suspend fun processOne() {
        val value = channel.receive()
        processTwo(value)
    }
    
    suspend fun processTwo(value: Int) {
        channel.send(value + 1)
    }
    
    suspend fun processThree() {
        channel.send(200)
        channel.receive()
    }
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val channel = Channel<Int>()
    val processor = Processor(channel)

    launch(dispatcher) {
        processor.processOne()
    }

    launch(dispatcher) {
        processor.processThree()
    }
}

class ChannelTest18 : BaseChannelTest() {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}