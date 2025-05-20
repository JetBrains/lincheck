/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.guide

import java.util.concurrent.*
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.*
import org.jetbrains.kotlinx.lincheck.traceagent.isInTraceDebuggerMode
import org.junit.Assume.assumeFalse
import org.junit.*


class ConcurrentHashMapTest {
    @Before
    fun setUp() {
        assumeFalse(isInTraceDebuggerMode) // .invocationsPerIteration must be 1 for the trace debugger
    }
    
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation
    public fun put(key: Int, value: Int) = map.put(key, value)

    //@Test // TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun modelCheckingTest() = ModelCheckingOptions()
        .actorsBefore(1)
        .actorsPerThread(1)
        .actorsAfter(0)
        .minimizeFailedScenario(false)
        .checkObstructionFreedom(true)
        .analyzeStdLib(true)
        .check(this::class)
}

class ConcurrentSkipListMapTest {
    @Before
    fun setUp() {
        assumeFalse(isInTraceDebuggerMode) // .invocationsPerIteration must be 1 for the trace debugger
    }
    
    private val map = ConcurrentSkipListMap<Int, Int>()

    @Operation
    public fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .analyzeStdLib(true)
        .check(this::class)
}
