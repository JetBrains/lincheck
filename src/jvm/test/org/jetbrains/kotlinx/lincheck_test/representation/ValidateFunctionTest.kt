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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.*

class ValidateFunctionTest {
    @Operation
    fun operation() {
        if (validateInvoked != 0) {
            error("The validation function should be called only at the end of the scenario")
        }
    }

    private var validateInvoked: Int = 0

    @Validate
    fun validateWithError(): Int {
        validateInvoked++
        if (validateInvoked == 1) error("Validation works!")
        return 0
    }

    @Test
    fun test() = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.ModelChecking
        addCustomScenario {
            initial {
                actor(::operation)
            }
            parallel {
                thread {
                    actor(::operation)
                }
            }
            post {
                actor(::operation)
            }
        }
        generateRandomScenarios = false
    }
        .checkImpl(this::class.java)
        .checkLincheckOutput("validation_function_failure.txt")

}
