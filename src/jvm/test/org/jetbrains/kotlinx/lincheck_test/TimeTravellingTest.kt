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
        val t1 = Thread {
            x.value++
        }
        val t2 = Thread {
            y.value++
        }
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        println("x=${x.value}")
        println("y=${y.value}")
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

}

