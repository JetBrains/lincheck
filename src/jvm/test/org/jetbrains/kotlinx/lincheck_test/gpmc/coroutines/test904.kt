/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test904
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test904.RunChecker904.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

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

fun configChannelA(classA: ClassA) = runBlocking(pool) {
    launch(pool) {
        classA.sendToChannel1(1)
        classA.receiveFromChannel2()
    }
}

fun configChannelB(classB: ClassB) = runBlocking(pool) {
    launch(pool) {
        classB.sendToChannel3(2)
        classB.receiveFromChannel4()
    }
}

fun initiateDeadlock(classA: ClassA, classB: ClassB) = runBlocking(pool) {
    val job1 = launch(pool) {
        classA.sendToChannel1(classB.receiveFromChannel4())
    }

    val job2 = launch(pool) {
        classB.sendToChannel3(classA.receiveFromChannel2())
    }

    job1.join()
    job2.join()
}

fun main(): Unit = runBlocking(pool) {
    val classA = ClassA()
    val classB = ClassB()

    launch(pool) {
        configChannelA(classA)
    }

    launch(pool) {
        configChannelB(classB)
    }

    initiateDeadlock(classA, classB)
}

class RunChecker904: BaseRunCoroutineTests(true) {
        companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        runBlocking(pool) { main() }
    }
}