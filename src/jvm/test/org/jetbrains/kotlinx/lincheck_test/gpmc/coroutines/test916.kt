/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test916
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test916.RunChecker916.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Ignore

class ChannelA(private val channel: Channel<Int>) {
    suspend fun produce() {
        for (i in 1..5) {
            channel.send(i)
        }
    }
}

class ChannelB(private val channel: Channel<Int>) {
    suspend fun consume(outChannel: Channel<Int>) {
        for (i in 1..5) {
            val received = channel.receive()
            outChannel.send(received * 2)
        }
    }
}

fun main(): Unit = runBlocking(pool) {
    val channelA = Channel<Int>()
    val channelB = Channel<Int>()

    val producer = ChannelA(channelA)
    val consumer = ChannelB(channelA)

    launch(pool) {
        producer.produce()
    }
    
    launch(pool) {
        consumer.consume(channelB)
    }

    for (i in 1..5) {
        check(channelB.receive() % 2 == 0)
    }
}

@Ignore("""
Check failed.
java.lang.IllegalStateException: Check failed.
	at org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy.runInvocation(ManagedStrategy.kt:245)
""")
class RunChecker916 : BaseRunCoroutineTests(false) {
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