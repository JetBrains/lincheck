/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test914
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test914.RunChecker914.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class FooBar {
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
        runBlocking(pool) {
            launch(pool) { alpha() }
            launch(pool) { beta() }
            launch(pool) { gamma() }
            launch(pool) { delta() }
            launch(pool) { epsilon() }
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

fun main(): Unit{
    FooBar().launchCoroutines()
}

class RunChecker914: BaseRunCoroutineTests(true) {
        companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}