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

import org.jetbrains.kotlinx.lincheck.TimeTravellingInjections.runWithLincheck
import org.junit.Test
import java.util.*
import kotlin.concurrent.thread

class TestClass {

    class IntHolder(@JvmField var value: Int)

    fun singleThreadedTest() {
        val x = IntHolder(0)
        x.value++
        println("x = ${x.value}")
    }

    fun multiThreadedTest() {
        val x = IntHolder(0)
        val y = IntHolder(0)
        val t1 = thread {
            x.value++
        }
        val t2 = thread {
            y.value++
        }
        t1.join()
        t2.join()
        println("x=${x.value}")
        println("y=${y.value}")
    }

    fun hashMapTest() {
        val m = HashMap<Int, String>()
        m[1] = "123"
    }

}

class TimeTravellingTest {

    private val testClass = TestClass::class.java

    @Test
    fun singleThreadedTest() {
        runWithLincheck(testClass.name, "singleThreadedTest")
    }

    @Test
    fun multiThreadedTest() {
        runWithLincheck(testClass.name, "multiThreadedTest")
    }

    @Test
    fun hashMapTest() {
        runWithLincheck(testClass.name, "hashMapTest")
    }

}

