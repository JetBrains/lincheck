/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package org.jetbrains.kotlinx.lincheck_benchmark

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.reflect.KClass
import org.junit.Test


abstract class AbstractLincheckBenchmark(
    private vararg val expectedFailures: KClass<out LincheckFailure>
) {

    @Test(timeout = TIMEOUT)
    fun benchmarkWithStressStrategy(): Unit = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.Stress
        configure()
    }.runTest()

    @Test(timeout = TIMEOUT)
    fun benchmarkWithModelCheckingStrategy(): Unit = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.ModelChecking
        configure()
    }.runTest()

    private fun LincheckOptions.runTest() {
        val failure = runTests(this@AbstractLincheckBenchmark::class.java, tracker = createRunTracker())
        if (failure == null) {
            assert(expectedFailures.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
        } else {
            assert(expectedFailures.contains(failure::class)) {
                "This test has failed with an unexpected error: \n $failure"
            }
        }
    }

    private fun LincheckOptionsImpl.configure() {
        maxThreads = 4
        maxOperationsInThread = 2
        minimizeFailedScenario = false
        iterations = 30
        invocationsPerIteration = 5000
        customize()
    }

    internal open fun LincheckOptions.customize() {}

    private fun createRunTracker() = object : RunTracker {

        private val scenarios = mutableListOf<ExecutionScenario>()

        override fun iterationStart(iteration: Int, scenario: ExecutionScenario) {
            scenarios.add(scenario)
        }

        override fun runEnd(
            name: String,
            testClass: Class<*>,
            options: LincheckOptions,
            failure: LincheckFailure?,
            exception: Throwable?,
            statistics: Statistics?
        ) {
            check(statistics != null)
            check(scenarios.size == statistics.iterationsCount)
            // TODO: check that all scenarios either have or do not have init/post parts
            // TODO: check that in each scenario all threads have same number of operations
            check(options is LincheckOptionsImpl)
            check(options.mode in listOf(LincheckMode.Stress, LincheckMode.ModelChecking))
            val benchmarkStatistics = BenchmarkStatistics.create(
                name = testClass.simpleName,
                mode = options.mode,
                runningTimeNano = statistics.runningTimeNano,
                iterationsCount = statistics.iterationsCount,
                invocationsCount = statistics.invocationsCount,
                scenariosStatistics = statistics.iterationsStatistics.mapIndexed { i, iterationStatistics ->
                    val scenario = scenarios[i]
                    ScenarioStatistics(
                        threads = scenario.nThreads,
                        operations = scenario.parallelExecution[0].size,
                        runningTimeNano = iterationStatistics.runningTimeNano,
                        averageInvocationTimeNano = iterationStatistics.averageInvocationTimeNano.toLong(),
                        invocationsCount = iterationStatistics.invocationsCount,
                    )
                }
            )
            benchmarksReporter.registerBenchmark(benchmarkStatistics)
        }

    }

}

private const val TIMEOUT = 5 * 60 * 1000L // 5 minutes