/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import org.junit.Assert.*

class FailedScenarioMinimizationTest: VerifierState() {
    @Volatile
    private var counter = 0

    @Operation
    fun inc() = counter++

    override fun extractState() = counter

    @Test
    fun testWithoutMinimization() {
        val options = StressOptions()
            .actorsPerThread(10)
            .invocationsPerIteration(1_000)
            .minimizeFailedScenario(false)
        try {
            LinChecker.check(FailedScenarioMinimizationTest::class.java, options)
            fail("Should fail with LincheckAssertionError")
        } catch (e: LincheckAssertionError) {
            val failedScenario = e.failure.scenario
            assertTrue("The init part should NOT be minimized", failedScenario.initExecution.isNotEmpty())
            assertTrue("The post part should NOT be minimized", failedScenario.postExecution.isNotEmpty())
            for (i in failedScenario.parallelExecution.indices) {
                assertEquals("The parallel part should NOT be minimized", 10, failedScenario.parallelExecution[i].size)
            }
        }
    }

    @Test
    fun testWithMinimization() {
        val options = StressOptions()
            .actorsPerThread(10)
            .invocationsPerIteration(1_000)
        try {
            LinChecker.check(FailedScenarioMinimizationTest::class.java, options)
            fail("Should fail with LincheckAssertionError")
        } catch (e: LincheckAssertionError) {
            val failedScenario = e.failure.scenario
            assertTrue("The init part should be minimized", failedScenario.initExecution.isEmpty())
            assertTrue("The post part should be minimized", failedScenario.postExecution.isEmpty())
            for (i in failedScenario.parallelExecution.indices) {
                assertEquals("The error should be reproduced with one operation per thread (Thread #${i+1})",
                    1, failedScenario.parallelExecution[i].size)
            }
        }
    }
}
