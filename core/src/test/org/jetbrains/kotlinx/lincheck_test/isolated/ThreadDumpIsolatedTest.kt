/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.isolated

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.datastructures.StressOptions
import org.junit.*

/**
 * This test checks that there is no old thread in thread dump.
 */
class ThreadDumpIsolatedTest {
    @Test
    fun test() {
        repeat(30) {
            val options = StressOptions()
                .minimizeFailedScenario(false)
                .iterations(1)
                .invocationsPerIteration(100_000)
                .invocationTimeout(100)
            val failure = options.checkImpl(DeadlockOnSynchronizedIsolatedTest::class.java)
            check(failure is TimeoutFailure) { "${ManagedDeadlockFailure::class.simpleName} was expected but ${failure?.javaClass} was obtained" }
            check(failure.threadDump.size == 2) { "thread dump for 2 threads expected, but for ${failure.threadDump.size} threads was detected" }
        }
    }
}
