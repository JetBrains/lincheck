/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.nvm

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.test.nvm.nrl.CounterTest
import org.junit.Test

internal class ParallelTestsTest {
    @Test
    fun test() {
        val aTest = Runnable { SmallCounterTest().testWithStressStrategy() }
        val executors = List(2) { Thread(aTest) }
        var er: Throwable? = null
        executors.forEach { it.setUncaughtExceptionHandler { _, e -> er = e } }
        executors.forEach { it.start() }
        executors.forEach { it.join() }
        er?.also { throw it }
    }
}

internal class SmallCounterTest : CounterTest() {
    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(2)
        actorsPerThread(2)
        actorsAfter(2)
        threads(2)
        iterations(10)
    }
}
