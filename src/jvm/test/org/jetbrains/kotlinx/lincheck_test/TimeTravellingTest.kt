/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck.TimeTravellingInjections.runWithLincheck
import org.junit.Test
import java.util.*

class TestClass {

    class IntHolder(@JvmField var value: Int)

    fun singleThreadedTest() {
        val x = IntHolder(0)
        x.value++
        println("x = ${x.value}")
    }

    fun hashMapTest() {
        val m = HashMap<Int, String>()
        m[1] = "123"
    }

    fun coroutinesTest() = runBlocking {
        val c = Channel<String>()
        launch {
            val message = c.receive()
            println("Message: $message")
        }
        c.send("hello, world!")
    }

}

class TimeTravellingTest {

    private val testClass = TestClass::class.java

    @Test
    fun singleThreadedTest() {
        runWithLincheck(testClass.name, "singleThreadedTest")
    }

    @Test
    fun hashMapTest() {
        runWithLincheck(testClass.name, "hashMapTest")
    }

    @Test
    fun coroutinesTest() {
        runWithLincheck(testClass.name, "coroutinesTest")
    }

    @Test
    fun mainTest() {
        runWithLincheck(MainKtEmulated::class.java.name, "main")
    }

}

class MainKtEmulated(val useless: Int) {

    companion object {
        @JvmStatic
        fun main() {
            val x = TestClass.IntHolder(1)
            x.value++
            println("Hello, world!")
        }
    }

}