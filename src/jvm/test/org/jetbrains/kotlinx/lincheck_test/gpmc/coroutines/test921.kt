/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test921
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test921.RunChecker921.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.*

class Processor(val channel: Channel<Int>) {
    suspend fun processOne() {
        val value = channel.receive()
        println("Processed One: $value")
        processTwo(value)
    }
    
    suspend fun processTwo(value: Int) {
        println("Processed Two: $value")
        channel.send(value + 1)
    }
    
    suspend fun processThree() {
        delay(500)
        val value = 100
        channel.send(value)
    }
    
    suspend fun processFour() {
        val value = channel.receive()
        println("Processed Four: $value")
    }
    
    suspend fun processFive() {
        channel.send(200)
        val value = channel.receive()
        println("Processed Five: $value")
    }
}

fun main(): Unit = runBlocking(pool) {
    val channel = Channel<Int>()
    val processor = Processor(channel)

    launch(pool) {
        processor.processOne()
    }

    launch(pool) {
        processor.processFive()
    }
}

class RunChecker921: BaseRunCoroutineTests(false) {
        companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}