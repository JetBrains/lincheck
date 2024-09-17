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

    fun singleThreadedTest() {
        val random = Random()
        val list = List(8) { random.nextInt(20) }.sorted()
        val result = list.binarySearch(5)
        println("list = $list")
        println("result = $result")
    }

}

class TimeTravellingTest {

    private val testClass = TestClass::class.java

    @Test
    fun singleThreadedTest() {
        runWithLincheck(testClass.name, "singleThreadedTest")
    }

}

