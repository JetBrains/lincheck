/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.io.Serializable

/**
 * This test checks that managed strategies do not switch
 * the execution thread at final field reads since they are
 * not interesting code locations for concurrent execution.
 * Otherwise, this test fails by timeout since
 * the number of invocations is set to [Int.MAX_VALUE].
 */
@ModelCheckingCTest(actorsBefore = 0, actorsAfter = 0, actorsPerThread = 50, invocationsPerIteration = Int.MAX_VALUE, iterations = 50, minimizeFailedScenario = false)
class FinalFieldReadingEliminationTest : VerifierState() {
    val primitiveValue: Int = 32
    val nonPrimitiveValue = ValueHolder(2)

    @Operation
    fun readPrimitive() = primitiveValue

    @Operation
    fun readNonPrimitive() = nonPrimitiveValue

    @Test(timeout = 100_000)
    fun test() {
        LinChecker.check(this::class.java)
    }

    override fun extractState(): Any = 0 // constant state

    data class ValueHolder(val value: Int) : Serializable
}
