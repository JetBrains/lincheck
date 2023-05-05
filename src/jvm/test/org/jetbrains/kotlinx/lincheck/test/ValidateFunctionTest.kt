/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
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
        val options = StressOptions().iterations(1)
                                     .invocationsPerIteration(1)
                                     .actorsBefore(3)
                                     .actorsAfter(10)
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
