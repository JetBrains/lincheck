/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.representation

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.test.util.*
import org.junit.*

/**
 * This test checks that all switches that are the first events in methods are lifted out of the methods in the trace.
 * E.g, instead of
 * ```
 * actor()
 *   method()
 *      switch
 *      READ
 *      ...
 * ```
 * should be
 * ```
 * actor()
 *   switch
 *   method()
 *      ...
 * ```
 */
class SwitchAsFirstMethodEventTest {
    private val counter = atomic(0)

    @Operation
    fun incTwiceAndGet(): Int {
        incAndGet()
        return incAndGet()
    }

    private fun incAndGet(): Int = incAndGetImpl()

    private fun incAndGetImpl() = counter.incrementAndGet()

    @Test
    fun test() = runModelCheckingTestAndCheckOutput("switch_as_first_method_event.txt") {
        actorsPerThread(1)
        actorsBefore(0)
        actorsAfter(0)
        requireStateEquivalenceImplCheck(false)
    }
}