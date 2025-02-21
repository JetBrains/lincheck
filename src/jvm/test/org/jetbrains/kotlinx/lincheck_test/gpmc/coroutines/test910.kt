/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test910
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test910.RunChecker910.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Ignore

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

fun main(): Unit = runBlocking(pool) {
    val channel1 = Channel<Int>()
    val channel2 = Channel<Int>()
    val channel3 = Channel<Int>()

    val processorA = ProcessorA(channel1, channel2)
    val processorB = ProcessorB(channel2, channel3)

    launch(pool) {
        processorA.process()
    }

    launch(pool) {
        channel1.send(1)
    }

    launch(pool) {
        processorB.process()
    }

    launch(pool) {
        val result = channel3.receive()
        check(result == 5)
    }
}

class RunChecker910 : BaseRunCoroutineTests(false, 1000) {
    companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        pool.use {
            runBlocking(pool) { main() }
        }
    }
}