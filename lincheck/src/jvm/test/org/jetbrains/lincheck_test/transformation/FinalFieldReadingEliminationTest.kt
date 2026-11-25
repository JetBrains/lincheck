/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.transformation

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.Test
import java.io.Serializable

/**
 * This test checks that managed strategies do not switch
 * the execution thread at final field reads since they are
 * not interesting code locations for concurrent execution.
 * Otherwise, this test fails by timeout since
 * the number of invocations is set to [Int.MAX_VALUE].
 */
class FinalFieldReadingEliminationTest {
    val primitiveValue: Int = 32
    val nonPrimitiveValue = ValueHolder(2)

    @Operation
    fun readPrimitive() = primitiveValue

    @Operation
    fun readNonPrimitive() = nonPrimitiveValue

    @Test(timeout = 100_000)
    fun test() {
        val settings = LincheckSettings {
            modelChecking()
            scenarioGeneration {
                operationsBefore = 0
                operationsAfter = 0
                operationsPerThread = 50
                invocationsPerScenario = Int.MAX_VALUE
                randomScenarios = 50
                minimizeFailedScenario = false
            }
        }
        runConcurrentTest(settings, this::class)
    }

    data class ValueHolder(val value: Int) : Serializable
}
