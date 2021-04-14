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

import org.jetbrains.kotlinx.lincheck.LincheckStressConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.native.concurrent.*
import kotlin.test.*

class ValidateFunctionTest : VerifierState() {
    val c = AtomicInt(0)

    fun inc() = c.addAndGet(1)

    override fun extractState() = c.value

    fun validateNoError() {}

    var validateInvoked: Int = 0

    // This function fails on the 5ht invocation
    fun validateWithError() {
        validateInvoked++
        if (validateInvoked == 5) error("Validation works!")
    }

    @Test
    fun test() {
        val f = LincheckStressConfiguration<ValidateFunctionTest>().apply {
            iterations(1)
            invocationsPerIteration(1)
            actorsBefore(3)
            actorsAfter(10)

            initialState { ValidateFunctionTest() }
            operation(ValidateFunctionTest::inc)
            validationFunction(ValidateFunctionTest::validateNoError, "validateNoError")
            validationFunction(ValidateFunctionTest::validateWithError, "validateWithError")
        }.checkImpl()
        assert(f != null && f is ValidationFailure && f.functionName == "validateWithError") {
            "This test should fail with a validation error"
        }
        val validationInvocations = f!!.scenario.initExecution.size + f.scenario.postExecution.size + 1
        assert(validationInvocations == 5) {
            "The scenario should have exactly 5 points to invoke validation functions, " +
                "see the resulting scenario below: \n${f.scenario}"
        }
    }

}
