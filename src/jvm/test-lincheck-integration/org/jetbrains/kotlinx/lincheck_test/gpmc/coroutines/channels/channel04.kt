/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.channel04

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels.BaseChannelTest

class ClassA {
    val channel1 = Channel<Int>()
    val channel2 = Channel<Int>()

    suspend fun sendToChannel1(value: Int) {
        channel1.send(value)
    }

    suspend fun receiveFromChannel2(): Int {
        return channel2.receive()
    }
}

class ClassB {
    val channel3 = Channel<Int>()
    val channel4 = Channel<Int>()

    suspend fun sendToChannel3(value: Int) {
        channel3.send(value)
    }

    suspend fun receiveFromChannel4(): Int {
        return channel4.receive()
    }
}

fun configChannelA(classA: ClassA, dispatcher: CoroutineDispatcher) = runBlocking(dispatcher) {
    launch(dispatcher) {
        classA.sendToChannel1(1)
        classA.receiveFromChannel2()
    }
}

fun configChannelB(classB: ClassB, dispatcher: CoroutineDispatcher) = runBlocking(dispatcher) {
    launch(dispatcher) {
        classB.sendToChannel3(2)
        classB.receiveFromChannel4()
    }
}

fun initiateDeadlock(
    classA: ClassA,
    classB: ClassB,
    dispatcher: CoroutineDispatcher
) = runBlocking(dispatcher) {
    val job1 = launch(dispatcher) {
        classA.sendToChannel1(classB.receiveFromChannel4())
    }

    val job2 = launch(dispatcher) {
        classB.sendToChannel3(classA.receiveFromChannel2())
    }

    job1.join()
    job2.join()
}

fun main(dispatcher: CoroutineDispatcher): Unit = runBlocking(dispatcher) {
    val classA = ClassA()
    val classB = ClassB()

    launch(dispatcher) {
        configChannelA(classA, dispatcher)
    }

    launch(dispatcher) {
        configChannelB(classB, dispatcher)
    }

    initiateDeadlock(classA, classB, dispatcher)
}

class ChannelTest04 : BaseChannelTest(true) {

    override fun block(dispatcher: CoroutineDispatcher) {
        runBlocking(dispatcher) { main(dispatcher) }
    }
}