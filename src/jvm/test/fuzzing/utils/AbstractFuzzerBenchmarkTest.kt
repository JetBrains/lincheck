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

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.coverage.CoverageOptions
import org.jetbrains.kotlinx.lincheck.fuzzing.coverage.toCoverage
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test
import kotlin.reflect.jvm.jvmName

abstract class AbstractFuzzerBenchmarkTest {
    @Test(expected = AssertionError::class)
    fun modelCheckingWithCoverageTest() {
        ModelCheckingOptions().apply {
            executionConfiguration()
            customize()
            customizeModelCheckingCoverage()
            check(this@AbstractFuzzerBenchmarkTest::class)
        }
    }

    @Test(expected = AssertionError::class)
    fun fuzzingWithCoverageTest() {
        ModelCheckingOptions().apply {
            executionConfiguration()
            customize()
            customizeFuzzingCoverage()
            check(this@AbstractFuzzerBenchmarkTest::class)
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
        ) { pr, res ->
            println(
                "Coverage: " +
                "edges=${pr.toCoverage().coveredBranchesCount()}, " +
                "branch=${res.branchCoverage}/${res.totalBranches}, " +
                "line=${res.lineCoverage}/${res.totalLines}"
            )
        })
    }
}