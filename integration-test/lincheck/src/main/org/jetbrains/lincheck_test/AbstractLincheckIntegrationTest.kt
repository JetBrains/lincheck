/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test

import org.jetbrains.kotlinx.lincheck.LincheckFailure
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenarioChecker
import org.junit.*
import org.junit.Assume.assumeTrue
import kotlin.reflect.*

abstract class AbstractLincheckIntegrationTest(
    private vararg val expectedFailures: KClass<out LincheckFailure>
) {
    open fun LincheckSettings.customize() {}

    private fun LincheckSettings.runInternalTest() {
        val failure = ExecutionScenarioChecker(
            this@AbstractLincheckIntegrationTest::class.java, copy(forLambda = false)
        ).detectFailure()
        if (failure === null) {
            assert(expectedFailures.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
        } else {
            checkFailureIsNotLincheckInternalBug(failure)
            checkTraceHasNoLincheckEvents(failure.toString())
            assert(expectedFailures.contains(failure::class)) {
                "This test has failed with an unexpected error: \n $failure"
            }
        }
    }

    @Test(timeout = TIMEOUT)
    fun testWithStressStrategy(): Unit = LincheckSettings {
        stress()
        scenarioGeneration { invocationsPerScenario = 5_000 }
        commonConfiguration()
    }.runInternalTest()

    @Test(timeout = TIMEOUT)
    // NOTE: please do not rename - the name is used to filter tests on CI in the "single-CPU" configuration:
    // https://teamcity.jetbrains.com/buildConfiguration/KotlinTools_KotlinxLincheck_BuildLinuxOnJava17OnlyOneCpu
    // We use this configuration to check the Lincheck's behavior on machines with a single CPU available.
    // We want to run only model checking tests in this configuration, because stress tests when run on
    // a single CPU might not detect any bugs.
    fun testWithModelCheckingStrategy(): Unit = LincheckSettings {
        modelChecking()
        scenarioGeneration { invocationsPerScenario = 1_000 }
        commonConfiguration()
    }.runInternalTest()

    @Test(timeout = TIMEOUT)
    fun testWithEventStructureStrategy(): Unit = LincheckSettings {
        modelChecking { enablePartialOrderReduction() }
        scenarioGeneration { invocationsPerScenario = 1_000 }
        commonConfiguration()
    }.runInternalTest()

    private fun LincheckSettings.commonConfiguration() {
        scenarioGeneration {
            randomScenarios = 30
            operationsBefore = 2
            threads = 3
            operationsPerThread = 2
            operationsAfter = 2
            minimizeFailedScenario = false
        }
        customize()
    }

    // Util function which allows us to exclude testing with event-structures for test cases that are not supported yet
    protected fun LincheckSettings.ignoreExperimentalModelChecking(): Unit {
        val algorithm = (strategy as? TestingStrategy.ModelChecking)?.resolveAlgorithm()
        assumeTrue(algorithm != TestingStrategy.ModelChecking.Algorithm.EventStructure)
    }
}

private const val TIMEOUT = 20 * 60_000L // 20 min
