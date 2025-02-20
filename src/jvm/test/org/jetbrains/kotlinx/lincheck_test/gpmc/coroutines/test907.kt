/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test907
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test907.RunChecker907.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

class ExampleClass {
    val channel1 = Channel<Int>()
    val channel2 = Channel<String>()
    val channel3 = Channel<Double>()
    val channel4 = Channel<Long>()
    
    suspend fun producer1() {
        channel1.send(1)
        delay(50)
        channel1.send(2)
    }

    suspend fun producer2() {
        channel2.send("Hello")
        delay(50)
        channel2.send("World")
    }

    suspend fun consumer1() {
        channel1.receive()
        channel2.receive()
//        println(channel1.receive())
//        println(channel2.receive())
    }

    suspend fun consumer2() {
        channel3.receive()
        channel4.receive()
//        println(channel3.receive())
//        println(channel4.receive())
    }
}

fun main(): Unit = runBlocking(pool) {
    val example = ExampleClass()

    launch(pool) {
        example.producer1()
    }
    
    launch(pool) {
        example.producer2()
    }
    
    launch(pool) {
        example.consumer1()
        example.consumer1()
    }
    
    launch(pool) {
        example.consumer2()
    }

    example.channel3.send(3.14)
    example.channel4.send(123456789L)
}

@org.junit.Ignore("'All unfinished threads are in deadlock' but should finish")
class RunChecker907 : BaseRunCoroutineTests(false) {
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