/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test903
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test903.RunChecker903.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Ignore
import org.junit.Test

@OptIn(DelicateCoroutinesApi::class)
fun producerA(channelA: Channel<Int>) = GlobalScope.launch(pool) {
    repeat(5) {
        channelA.send(it)
    }
    channelA.close()
}

@OptIn(DelicateCoroutinesApi::class)
fun producerB(channelB: Channel<Int>, channelA: Channel<Int>) = GlobalScope.launch(pool) {
    repeat(5) {
        val value = channelA.receive() * 2
        channelB.send(value)
    }
    channelB.close()
}

fun main(): Unit = runBlocking(pool) {
    val channelA = Channel<Int>()
    val channelB = Channel<Int>()

    producerA(channelA)
    producerB(channelB, channelA)

    repeat(5) {
        launch(pool) {
            check(channelB.receive() % 2 == 0)
        }
    }
}

@Ignore("""
java.lang.IllegalStateException: Check failed. 
    at org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.runInvocation(ManagedStrategy.kt:245)
""")
class RunChecker903: BaseRunCoroutineTests(false) {
    companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}