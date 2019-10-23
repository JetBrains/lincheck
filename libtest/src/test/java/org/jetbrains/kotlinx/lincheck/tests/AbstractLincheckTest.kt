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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.lang.AssertionError
import java.lang.IllegalStateException

/**
 * An abstraction for testing all lincheck strategies
 */
abstract class AbstractLincheckTest(val shouldFail: Boolean, val checkObstructionFreedom: Boolean = false) : VerifierState() {
    @Test
    fun testStressStrategy() {
        runTest(StressOptions())
    }

    private fun runTest(options: Options<*, *>) {
        var failed = false

        try {
            val report = LinChecker.check(this.javaClass, options)
        } catch (e: AssertionError) {
            failed = true
            if (failed && !shouldFail)
                throw e
        }
    }
}
