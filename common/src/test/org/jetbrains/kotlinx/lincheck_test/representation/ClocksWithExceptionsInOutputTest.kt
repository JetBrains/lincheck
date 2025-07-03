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

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
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
    fun `should add stackTrace to output`() = ModelCheckingOptions().apply {
        actorsPerThread(2)
        minimizeFailedScenario(false)
    }
    .checkImpl(this::class.java) { failure ->
        failure.checkLincheckOutput("clocks_and_exceptions")
    }

}