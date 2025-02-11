/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test912
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test912.RunChecker912.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class ChannelA(val channel: Channel<Int>)
class ChannelB(val channel: Channel<Int>)
class ChannelC(val channel: Channel<Int>)

fun first(channelA: ChannelA, channelB: ChannelB) {
    runBlocking(pool) {
        val jobA = launch(pool) {
            channelA.channel.send(1)
            channelB.channel.receive()
        }
        jobA.start()
    }
}

fun second(channelB: ChannelB, channelC: ChannelC) {
    runBlocking(pool) {
        val jobB = launch(pool) {
            channelB.channel.send(2)
            channelC.channel.receive()
        }
        jobB.start()
    }
}

fun third(channelC: ChannelC, channelA: ChannelA) {
    runBlocking(pool) {
        val jobC = launch(pool) {
            channelC.channel.send(3)
            channelA.channel.receive()
        }
        jobC.start()
    }
}

class ChannelD(val channel: Channel<Int>)
fun fourth(channelD: ChannelD, channelA: ChannelA) {
    runBlocking(pool) {
        val jobD = launch(pool) {
            channelD.channel.send(4)
            channelA.channel.receive()
        }
        jobD.start()
    }
}

fun main(): Unit{
    val channelA = Channel<Int>()
    val channelB = Channel<Int>()
    val channelC = Channel<Int>()
    val channelD = Channel<Int>()
    
    val chanA = ChannelA(channelA)
    val chanB = ChannelB(channelB)
    val chanC = ChannelC(channelC)
    val chanD = ChannelD(channelD)
    
    first(chanA, chanB)
    second(chanB, chanC)
    third(chanC, chanA)
    fourth(chanD, chanA)
}

class RunChecker912: BaseRunCoroutineTests(true) {
        companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}