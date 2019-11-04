/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.tests

import org.jetbrains.kotlinx.lincheck.ErrorType
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.linCheckAnalyze
import org.jetbrains.kotlinx.lincheck.strategy.modelchecking.ModelCheckingCTest
import org.jetbrains.kotlinx.lincheck.strategy.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.randomswitch.RandomSwitchOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.lang.AssertionError

/**
 * An abstraction for testing all lincheck strategies
 */
abstract class AbstractLinCheckTest(private var expectedError: ErrorType) : VerifierState() {
    @Test
    fun testStressStrategy() {
        // stress strategy can not distinguish livelock from deadlock
        if (expectedError == ErrorType.LIVELOCK)
            expectedError = ErrorType.DEADLOCK
        // stress strategy can not check obstruction freedom
        if (expectedError == ErrorType.OBSTRUCTION_FREEDOM_VIOLATED)
            expectedError = ErrorType.NO_ERROR
        runTest(StressOptions())
    }

    @Test
    fun testModelCheckingStrategy() {
        runTest(ModelCheckingOptions())
    }

    @Test
    fun testRandomSwitchStrategy() {
        runTest(RandomSwitchOptions())
    }

    private fun runTest(options: Options<*, *>) {
        val report = linCheckAnalyze(this.javaClass, options)
        assertEquals(report.errorDetails, expectedError, report.errorType)
    }
}
