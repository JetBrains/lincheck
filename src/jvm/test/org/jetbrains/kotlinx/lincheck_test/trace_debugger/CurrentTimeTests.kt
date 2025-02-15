/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.trace_debugger

import org.jetbrains.kotlinx.lincheck.annotations.Operation

abstract class CurrentTimeTest : AbstractDeterministicTest()

class ActualTimeMillisTest : CurrentTimeTest() {
    @Operation
    fun operation() = List(10) {
        val start = System.currentTimeMillis()
        Thread.sleep(1)
        val middle = System.currentTimeMillis()
        Thread.sleep(1000)
        val end = System.currentTimeMillis()
        require(middle - start < 500) { "Wrong time: ${middle - start}" }
        require(end - middle > 500) { "Wrong time: ${end - middle}" }
        "$start $middle $end"
    }
}

class ActualTimeNanosTest : CurrentTimeTest() {
    @Operation
    fun operation() = List(10) {
        val start = System.nanoTime()
        Thread.sleep(1)
        val middle = System.nanoTime()
        Thread.sleep(1000)
        val end = System.nanoTime()
        require(middle - start < 500_000_000) { "Wrong time: ${middle - start}" }
        require(end - middle > 500_000_000) { "Wrong time: ${end - middle}" }
        "$start $middle $end"
    }
}

class CurrentTimeNanoTest : CurrentTimeTest() {
    @Operation
    fun operation() = System.nanoTime()
}

class CurrentTimeMillisTest : CurrentTimeTest() {
    @Operation
    fun operation() = System.currentTimeMillis()
}
