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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.test.*
import org.junit.*

/**
 * This test checks `verboseTrace` option that makes Lincheck log all execution events.
 */
class VerboseTraceTest {
    private var levelZeroCounter = 0
    private var levelOneCounter = 0
    private var levelTwoCounter = 0
    private var counter = 0

    @Operation
    fun operation(): Int {
        levelZeroCounter = 1
        levelOneEvent()
        return criticalSection()
    }

    private fun criticalSection(): Int = counter++

    private fun levelOneEvent() {
        levelTwoEvent()
        levelOneCounter = 2
    }

    private fun levelTwoEvent() {
        levelTwoCounter = 1
    }

    @Test
    fun test() {
        val failure = ModelCheckingOptions()
            .actorsAfter(0)
            .actorsBefore(0)
            .actorsPerThread(1)
            .verboseTrace(true)
            .checkImpl(this::class.java)
        checkNotNull(failure) { "test should fail" }
        val log = failure.toString()
        check("  criticalSection" in log) { "An intermediate method call was not logged or has an incorrect indentation" }
        check("    counter.READ" in log)
        check("  levelZeroCounter" in log)
        check("  levelOneEvent" in log) { "An intermediate method call was not logged or has an incorrect indentation" }
        check("    levelOneCounter" in log)
        check("    levelTwoEvent" in log) { "An intermediate method call was not logged or has an incorrect indentation" }
        check("      levelTwoCounter" in log)
        checkTraceHasNoLincheckEvents(log)
    }
}