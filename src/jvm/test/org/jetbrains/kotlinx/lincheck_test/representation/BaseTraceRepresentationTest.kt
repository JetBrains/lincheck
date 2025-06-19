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

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test

/**
 * Failing test that checks that output using [outputFileName].
 * The goal is to place the logic to check trace in the [actionsForTrace] method.
 */
abstract class BaseTraceRepresentationTest(private val outputFileName: String) {

    /**
     * Implement me and place the logic to check its trace.
     */
    @Operation
    abstract fun operation()

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::operation) }
            }
        }
        // to trigger the lincheck failure, we use the always failing verifier
        .verifier(FailingVerifier::class.java)
        .iterations(0)
        .apply { customize() }
        .checkImpl(this::class.java) { failure ->
            failure.checkLincheckOutput(outputFileName)
        }

    open fun ModelCheckingOptions.customize() {}

}

class FailingVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?) = false
}
