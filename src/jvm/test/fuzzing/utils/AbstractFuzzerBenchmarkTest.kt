/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package fuzzing.utils

import fuzzing.stats.BenchmarkStats
import fuzzing.stats.TestType
import fuzzing.stats.benchmarkCollector
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.coverage.CoverageOptions
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test
import kotlin.reflect.jvm.jvmName

abstract class AbstractFuzzerBenchmarkTest {
    @Test(expected = AssertionError::class)
    fun modelCheckingWithCoverageTest() {
        val stats = BenchmarkStats()
        try {
            ModelCheckingOptions().apply {
                logLevel(LoggingLevel.INFO)
                executionConfiguration()
                customize()
                customizeModelCheckingCoverage()
                minimizeFailedScenario(false)
                check(this@AbstractFuzzerBenchmarkTest::class, stats)
            }
        } finally {
            val testName = this@AbstractFuzzerBenchmarkTest::class.jvmName.replace("fuzzing.", "")
            println("$testName: \n$stats")
            stats.type = TestType.MODEL_CHECKING
            benchmarkCollector.add(testName, stats)
            benchmarkCollector.plotEachAndClear()
            //benchmarkCollector.saveJsonEachAndClear()
        }
    }

    @Test(expected = AssertionError::class)
    fun fuzzingWithCoverageTest() {
        val stats = BenchmarkStats()
        try {
            ModelCheckingOptions().apply {
                executionConfiguration()
                customize()
                customizeFuzzingCoverage()
                minimizeFailedScenario(false)
                check(this@AbstractFuzzerBenchmarkTest::class, stats)
            }
        }
        finally {
            val testName = this@AbstractFuzzerBenchmarkTest::class.jvmName.replace("fuzzing.", "")
            println("$testName: \n$stats")
            stats.type = TestType.FUZZING
            benchmarkCollector.add(testName, stats)
            //benchmarkCollector.saveJsonEachAndClear()
        }
    }

    /** override to modify coverage parameters for model checking */
    open fun <O: Options<O, *>> O.customizeModelCheckingCoverage() {
        coverageConfigurationForModelChecking()
    }

    /** override to modify coverage parameters for fuzzing */
    open fun <O: Options<O, *>> O.customizeFuzzingCoverage() {
        coverageConfigurationForFuzzing()
    }

    /** override to modify execution parameters */
    open fun <O: Options<O, *>> O.customize() {}

    protected fun <O: Options<O, *>> O.coverageConfigurationForModelChecking(
        excludePatterns: List<String> = emptyList(),
        includePatterns: List<String> = emptyList(),
    ) {
        coverageConfiguration(excludePatterns, includePatterns, fuzz = false)
    }

    protected fun <O: Options<O, *>> O.coverageConfigurationForFuzzing(
        excludePatterns: List<String> = emptyList(),
        includePatterns: List<String> = emptyList(),
    ) {
        coverageConfiguration(excludePatterns, includePatterns, fuzz = true)
    }

    private fun <O: Options<O, *>> O.executionConfiguration() {
        iterations(100)
        threads(3)
        actorsPerThread(4)
        actorsBefore(1)
        actorsAfter(1)
        when (this) {
            // Smart cast as invocations are not a general property
            is StressOptions -> invocationsPerIteration(10000)
            is ModelCheckingOptions -> invocationsPerIteration(10000)
        }
    }

    private fun <O: Options<O, *>> O.coverageConfiguration(
        excludePatterns: List<String> = emptyList(),
        includePatterns: List<String> = emptyList(),
        fuzz: Boolean = false
    ) {
        withCoverage(CoverageOptions(
            excludePatterns = listOf(AbstractFuzzerBenchmarkTest::class.jvmName) + excludePatterns,
            includePatterns = includePatterns,
            fuzz = fuzz
        )
//        { pr, res ->
//            println(
//                "Coverage: " +
//                "edges=${pr.toCoverage().coveredBranchesCount()}, " +
//                "branch=${res.branchCoverage}/${res.totalBranches}, " +
//                "line=${res.lineCoverage}/${res.totalLines}"
//            )
//        }
        )
    }
}