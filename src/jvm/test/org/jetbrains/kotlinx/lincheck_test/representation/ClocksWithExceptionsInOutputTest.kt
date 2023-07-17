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
import org.jetbrains.kotlinx.lincheck.LincheckOptionsImpl
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.Test

/**
 * Checks output when both messages about clocks and exceptions are present in output.
 */
@Suppress("UNUSED")
class ClocksWithExceptionsInOutputTest {

    private var canEnterForbiddenSection = false

    @Operation
    fun operation1() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
    }

    @Operation
    fun operation2() = check(!canEnterForbiddenSection) { "Violating exception" }

    @Test
    fun `should add stackTrace to output`() = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.ModelChecking
        addCustomScenario {
            parallel {
                thread {
                    actor(::operation1)
                    actor(::operation1)
                }
                thread {
                    actor(::operation2)
                }
            }
        }
        generateRandomScenarios = false
        minimizeFailedScenario = false
    }
        .checkImpl(this::class.java)
        .checkLincheckOutput("clocks_and_exceptions.txt")

}