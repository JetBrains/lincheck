/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test913
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test913.RunChecker913.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class Producer(private val channel: Channel<Int>) {
    suspend fun produce() {
        for (i in 1..3) {
            println("Producing $i")
            channel.send(i)
        }
    }
}

class Consumer(private val channel: Channel<Int>) {
    suspend fun consume() {
        for (i in 1..3) {
            val value = channel.receive()
            println("Consuming $value")
        }
    }
}

class Processor(private val channel: Channel<Int>) {
    suspend fun process() {
        val value = channel.receive()
        println("Processing $value")
    }
}

fun main(): Unit = runBlocking(pool) {
    val channel = Channel<Int>()

    val producer = Producer(channel)
    val consumer = Consumer(channel)
    val processor = Processor(channel)

    launch(pool) { producer.produce() }
    launch(pool) { consumer.consume() }
    launch(pool) { processor.process() }
    launch(pool) { processor.process() }
    launch(pool) { producer.produce() }
    
    println("This will never get printed due to deadlock")
}

class RunChecker913: BaseRunCoroutineTests(true) {
        companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}