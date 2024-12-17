/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.Lincheck
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test


abstract class BaseRunWithLambdaRepresentationTest(private val outputFileName: String) {
    /**
     * Implement me and place the logic to check its trace.
     */
    @Operation
    abstract fun operation()

    @Test
    fun testRunWithModelChecker() {
        val failure = Lincheck.verifyWithModelChecker(
            verifierClass = FailingVerifier::class.java
        ) {
            operation()
        }
        failure.checkLincheckOutput(outputFileName)
    }
}
