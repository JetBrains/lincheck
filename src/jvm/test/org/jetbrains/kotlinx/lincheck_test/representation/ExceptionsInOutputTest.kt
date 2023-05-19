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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck_test.util.runModelCheckingTestAndCheckOutput
import org.junit.Test

@Suppress("UNUSED")
class ExceptionsInOutputTest {

    private var canEnterForbiddenSection = false

    @Operation
    fun operation1() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
        error("Legal exception")
    }

    @Operation
    fun operation2() = check(!canEnterForbiddenSection) { "Violating exception" }

    @Test
    fun `should add stackTrace to output`() = runModelCheckingTestAndCheckOutput("exceptions_in_output.txt") {
        actorsBefore(2)
    }
}