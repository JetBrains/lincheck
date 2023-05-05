/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.representation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.test.runner.*
import org.junit.*

/**
 * This test checks that there is no old thread in thread dump.
 */
class ThreadDumpTest {
    @Test
    fun test() {
        val iterations = 30
        repeat(iterations) {
            val options = StressOptions()
                .minimizeFailedScenario(false)
                .iterations(100_000)
                .invocationsPerIteration(1)
                .invocationTimeout(100)
            val failure = options.checkImpl(DeadlockOnSynchronizedTest::class.java)
            check(failure is DeadlockWithDumpFailure) { "${DeadlockWithDumpFailure::class.simpleName} was expected but ${failure?.javaClass} was obtained"}
            check(failure.threadDump.size == 2) { "thread dump for 2 threads expected, but for ${failure.threadDump.size} threads was detected"}
        }
    }
}
