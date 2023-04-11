/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
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
    @Suppress("DEPRECATION_ERROR")
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
