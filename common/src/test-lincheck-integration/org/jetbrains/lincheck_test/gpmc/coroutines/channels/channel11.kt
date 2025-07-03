/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel11

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class ChannelA(val channel: Channel<Int>)
class ChannelB(val channel: Channel<Int>)
class ChannelC(val channel: Channel<Int>)

fun first(channelA: ChannelA, channelB: ChannelB, dispatcher: CoroutineDispatcher) {
    runBlocking(dispatcher) {
        val jobA = launch(dispatcher) {
            channelA.channel.send(1)  // deadlocks here
            channelB.channel.receive()
        }
        jobA.start()
    }
}

fun second(channelB: ChannelB, channelC: ChannelC, dispatcher: CoroutineDispatcher) {
    runBlocking(dispatcher) {
        val jobB = launch(dispatcher) {
            channelB.channel.send(2)
            channelC.channel.receive()
        }
        jobB.start()
    }
}

fun third(channelC: ChannelC, channelA: ChannelA, dispatcher: CoroutineDispatcher) {
    runBlocking(dispatcher) {
        val jobC = launch(dispatcher) {
            channelC.channel.send(3)
            channelA.channel.receive()
        }
        jobC.start()
    }
}

class ChannelD(val channel: Channel<Int>)
fun fourth(channelD: ChannelD, channelA: ChannelA, dispatcher: CoroutineDispatcher) {
    runBlocking(dispatcher) {
        val jobD = launch(dispatcher) {
            channelD.channel.send(4)
            channelA.channel.receive()
        }
        jobD.start()
    }
}

fun main(dispatcher: CoroutineDispatcher) {
    val channelA = Channel<Int>()
    val channelB = Channel<Int>()
    val channelC = Channel<Int>()
    val channelD = Channel<Int>()
    
    val chanA = ChannelA(channelA)
    val chanB = ChannelB(channelB)
    val chanC = ChannelC(channelC)
    val chanD = ChannelD(channelD)
    
    first(chanA, chanB, dispatcher) // this invocation will block
    second(chanB, chanC, dispatcher)
    third(chanC, chanA, dispatcher)
    fourth(chanD, chanA, dispatcher)
}

class ChannelTest11 : BaseChannelTest(true) {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}