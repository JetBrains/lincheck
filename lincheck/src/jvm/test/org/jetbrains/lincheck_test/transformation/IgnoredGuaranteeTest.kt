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
import org.jetbrains.lincheck.datastructures.forClasses
import org.junit.*

/**
 * This test checks that managed strategies do not try to switch
 * thread context inside methods that are marked as ignored.
 * The ignored method should not be considered as a one that
 * performs reads or writes, so that there is no need to switch
 * the execution after an ignored method invocation.
 *
 * If the ignored method is not processed properly, this test fails
 * by timeout since the number of invocations is set to Int.MAX_VALUE.
 */
class IgnoredGuaranteeTest {
    var value: Int = 0

    @Operation
    fun operation() = inc()

    private fun inc(): Int {
        return value++
    }

    @Test(timeout = 100_000)
    fun test() {
        runConcurrentTest(LincheckSettings {
            scenarioGeneration {
                operationsBefore = 0
                operationsAfter = 0
                operationsPerThread = 100
                randomScenarios = 50
                invocationsPerScenario = Int.MAX_VALUE
            }
            modelChecking { addGuarantee(forClasses(IgnoredGuaranteeTest::class).methods("inc").ignore()) }
        }, this::class)
    }
}
