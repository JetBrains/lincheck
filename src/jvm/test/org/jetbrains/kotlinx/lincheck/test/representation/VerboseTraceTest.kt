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
import org.jetbrains.kotlinx.lincheck.test.util.logVerbosePart
import org.junit.*

/**
 * This test checks that makes Lincheck log all execution events in detailed part of output.
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
            .requireStateEquivalenceImplCheck(false)
            .checkImpl(this::class.java)
        checkNotNull(failure) { "test should fail" }
        val log = failure.toString()

        check(Messages.PARALLEL_PART in log) { "No short trace in log output" }
        check(Messages.DETAILED_PARALLEL_PART in log) { "No verbose trace in log output" }

        val verboseTraceLog = logVerbosePart(log)

        check("  criticalSection" in verboseTraceLog) { "An intermediate method call was not logged or has an incorrect indentation" }
        check("    counter.READ" in verboseTraceLog)
        check("  levelZeroCounter" in verboseTraceLog)
        check("  levelOneEvent" in verboseTraceLog) { "An intermediate method call was not logged or has an incorrect indentation" }
        check("    levelOneCounter" in verboseTraceLog)
        check("    levelTwoEvent" in verboseTraceLog) { "An intermediate method call was not logged or has an incorrect indentation" }
        check("      levelTwoCounter" in verboseTraceLog)

        checkTraceHasNoLincheckEvents(log)
    }
}