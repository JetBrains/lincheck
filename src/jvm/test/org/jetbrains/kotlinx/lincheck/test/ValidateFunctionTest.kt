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
package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.concurrent.atomic.*

class ValidateFunctionTest : VerifierState() {
    val c = AtomicInteger()

    @Operation
    fun inc() = c.incrementAndGet()

    override fun extractState() = c.get()

    @Validate
    fun validateNoError() {}

    var validateInvoked: Int = 0

    // This function fails on the 5ht invocation
    @Validate
    fun validateWithError() {
        validateInvoked++
        if (validateInvoked == 5) error("Validation works!")
    }

    @Test
    fun test() {
        val options = LincheckOptions {
            this as LincheckOptionsImpl
            mode = LincheckMode.Stress
        }
        options as LincheckOptionsImpl
        val f = options.checkImpl(this::class.java)!!
        assert(f is ValidationFailure && f.functionName == "validateWithError") {
            "This test should fail with a validation error"
        }
        val validationInvocations = f.scenario.initExecution.size + f.scenario.postExecution.size + 1
        assert(validationInvocations == 5) {
            "The scenario should have exactly 5 points to invoke validation functions, " +
            "see the resulting scenario below: \n${f.scenario}"
        }
    }

}
