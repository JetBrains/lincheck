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
import org.junit.Ignore

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

fun main(): Unit = runBlocking(pool) {
    val channel = Channel<Int>()
    val processor = Processor(channel)

    launch(pool) {
        processor.processOne()
    }

    launch(pool) {
        processor.processThree()
    }
}

@Ignore("""
java.lang.IllegalStateException: Check failed.
	at org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.runInvocation(ManagedStrategy.kt:245)
""")
class RunChecker921 : BaseRunCoroutineTests(false) {
    companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}