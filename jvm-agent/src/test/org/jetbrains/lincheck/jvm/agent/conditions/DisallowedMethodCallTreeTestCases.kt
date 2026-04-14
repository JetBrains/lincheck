/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.conditions

/**
 * Test cases for verifying tree representation.
 * This file is separate to keep line numbers stable.
 */
object DisallowedMethodCallTreeTestCases {
    @JvmField
    var counter: Int = 0

    @JvmField
    var array: IntArray = IntArray(10)

    @JvmStatic
    fun simpleFieldWrite() {
        counter = 42
    }

    @JvmStatic
    fun callsMethodThatWritesField() {
        simpleFieldWrite()
    }

    @JvmStatic
    fun multipleViolations() {
        counter = 1
        array[0] = 2
    }

    @JvmStatic
    fun level3() {
        counter = 100
    }

    @JvmStatic
    fun level2() {
        level3()
    }

    @JvmStatic
    fun level1() {
        level2()
    }

    @JvmStatic
    fun complexCase() {
        array[0] = 1
        callsMethodThatWritesField()
    }

    @JvmStatic
    fun useSynchronized() {
        synchronized(DisallowedMethodCallTreeTestCases::class.java) {
            // synchronized block
        }
    }

    @JvmStatic
    fun simpleLoop() {
        var sum = 0
        for (i in 1..10) {
            sum += i
        }
    }

    @JvmStatic
    fun whileLoopExample() {
        var i = 0
        while (i < 10) {
            i++
        }
    }

    @JvmStatic
    fun loopWithFieldWrite() {
        var sum = 0
        for (i in 1..5) {
            sum += i
        }
    }

    @JvmStatic
    fun forEachLoop() {
        val list = listOf(1, 2, 3, 4, 5)
        var sum = 0
        list.forEach { sum += it }
    }

    @JvmStatic
    fun repeatLoop() {
        var sum = 0
        repeat(10) { sum += it }
    }

    @JvmStatic
    fun multipleLoops() {
        var sum = 0
        for (i in 1..5) {
            sum += i
        }
        for (j in 1..3) {
            sum += j
        }
    }
}
