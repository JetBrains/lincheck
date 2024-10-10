/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.trace_debugger

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.junit.Test


abstract class NativeCallTest {
    abstract fun operation(): Any

    @Test
    fun test() {
        val failure = ModelCheckingOptions()
            .actorsBefore(0)
            .actorsAfter(0)
            .threads(1)
            .actorsPerThread(1)
            .verifier(FailingVerifier::class.java)
            .checkImpl(this::class.java)
        assert(failure is IncorrectResultsFailure)
    }
}

class CurrentTimeMillisTest : NativeCallTest() {
    @Operation
    override fun operation() = System.currentTimeMillis()
}

@Suppress("UNUSED_PARAMETER")
class FailingVerifier(sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?) = false
}