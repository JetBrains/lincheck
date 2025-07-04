/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.lincheck.datastructures.ManagedCTestConfiguration
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.*
import java.lang.IllegalStateException

/**
 * Test verifies validation function representation in the trace.
 * Also verifies that validation function is invoked only at the end of the scenario execution.
 * This test contains two treads to verify that the output is correctly formatted in non-trivial case.
 */
@Suppress("unused")
class ValidationFunctionCallTest {

    @Volatile
    private var validateInvoked: Int = 0

    @Operation
    fun operation() {
        if (validateInvoked != 0) {
            error("The validation function should be called only at the end of the scenario")
        }
    }

    @Validate
    fun validateWithError(): Int {
        validateInvoked++
        if (validateInvoked == 1) error("Validation works!")
        return 0
    }

    @Test
    fun test() = ModelCheckingOptions().apply {
        addCustomScenario {
            initial {
                actor(::operation)
            }
            parallel {
                thread {
                    actor(::operation)
                }
                thread {
                    actor(::operation)
                }
            }
            post {
                actor(::operation)
            }
        }
    }
    .checkImpl(this::class.java) { failure ->
        failure.checkLincheckOutput("validation_function_failure")
    }

}

/**
 * Checks the case when a test is failed due to incorrect execution results but
 *  the validation function is present and passed successfully.
 *
 *  In the expected output, we check that validation function internals is not present in the trace.
 */
class IncorrectResultsFailureWithCorrectValidationFunctionTest {

    @Volatile
    var counter: Int = 0

    @Operation
    fun inc(): Int = counter++

    @Operation
    fun get(): Int = counter

    @Validate
    fun validate() = check(counter >= 0)

    @Test
    fun test() = ModelCheckingOptions()
        .checkImpl(this::class.java)
        .checkLincheckOutput("incorrect_results_with_validation_function")
}

class MoreThenOneValidationFunctionFailureTest {

    @Operation
    fun operation() = 1

    @Validate
    fun firstCheck() {}

    @Validate
    fun secondCheck() {}

    @Test
    fun test() = ModelCheckingOptions()
        .checkFailsWithException<IllegalStateException>(this::class.java, "two_validation_functions_exception")
}

class ValidationFunctionLongLoopTest {
    var a = 0

    val loopCount = 1024

    init {
        check(loopCount > ManagedCTestConfiguration.DEFAULT_HANGING_DETECTION_THRESHOLD)
        check(loopCount < ManagedCTestConfiguration.DEFAULT_LIVELOCK_EVENTS_THRESHOLD)
    }

    @Operation
    fun operation() {
        a++
    }

    @Validate
    fun validate() {
        // check that long-running loops inside a validation function
        // do not trigger active-lock failure
        repeat(loopCount) {
            a++
        }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .iterations(1)
        .actorsBefore(0)
        .actorsAfter(0)
        .actorsPerThread(1)
        .minimizeFailedScenario(false)
        .check(this::class.java)
}