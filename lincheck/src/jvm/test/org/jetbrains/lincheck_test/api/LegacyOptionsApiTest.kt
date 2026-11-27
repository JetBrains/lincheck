/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
@file:Suppress("DEPRECATION")

package org.jetbrains.lincheck_test.api

import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.lincheck.IncorrectResultsFailure
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.RandomProvider
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.toSettings
import org.jetbrains.lincheck.scenario.ExecutionGenerator
import org.jetbrains.lincheck.util.LoggingLevel
import org.junit.Assert.*
import org.junit.Test

class LegacyOptionsApiTest {
    @Test
    fun stressOptionsChecksCustomScenarioAgainstSequentialSpecification() {
        val options: Options<*, *> = StressOptions()
            .iterations(0)
            .invocationsPerIteration(1)
            .sequentialSpecification(SequentialValue::class.java)
            .addCustomScenario {
                parallel {
                    thread { actor(IncorrectValue::read) }
                }
            }

        val error = assertThrows(LincheckAssertionError::class.java) { options.check(IncorrectValue::class) }
        assertTrue(error.failure is IncorrectResultsFailure)
    }

    @Test
    fun modelCheckingOptionsChecksGeneratedScenario() {
        val options = ModelCheckingOptions()
            .iterations(1)
            .invocationsPerIteration(1)
            .threads(1)
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
            .sequentialSpecification(SequentialValue::class.java)

        val error = assertThrows(LincheckAssertionError::class.java) { options.check(IncorrectValue::class.java) }
        assertTrue(error.failure is IncorrectResultsFailure)
    }

    @Test
    fun linCheckerAcceptsLegacyOptions() {
        val options = StressOptions()
            .iterations(1)
            .invocationsPerIteration(1)
            .sequentialSpecification(SequentialValue::class.java)

        val error = assertThrows(LincheckAssertionError::class.java) {
            LinChecker.check(IncorrectValue::class.java, options)
        }
        assertTrue(error.failure is IncorrectResultsFailure)
    }

    @Test
    fun legacyOptionsAndSettingsShareDefaults() {
        val settings = LincheckSettings()
        val strategy = TestingStrategy.ModelChecking()
        val options = ModelCheckingOptions()
        assertSame(options, options.logLevel(LoggingLevel.OFF))
        val legacy = options.toSettings()
        val legacyStrategy = legacy.strategy as TestingStrategy.ModelChecking
        assertEquals(settings.invocationTimeoutMs, legacy.invocationTimeoutMs)
        assertTrue(legacy.scenarioGeneration.printExecutionScenarios)
        assertEquals(settings.scenarioGeneration.randomScenarios, legacy.scenarioGeneration.randomScenarios)
        assertEquals(settings.scenarioGeneration.invocationsPerScenario, legacy.scenarioGeneration.invocationsPerScenario)
        assertEquals(strategy.analyzeStdLib, legacyStrategy.analyzeStdLib)
        assertEquals(strategy.checkObstructionFreedom, legacyStrategy.checkObstructionFreedom)
        assertEquals(strategy.loopDetection.loopBound, legacyStrategy.loopDetection.loopBound)
        assertEquals(strategy.loopDetection.recursionBound, legacyStrategy.loopDetection.recursionBound)
    }

    @Test
    fun optionsRejectGeneratorWithOnlyLegacyConstructor() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ModelCheckingOptions().executionGenerator(LegacyOptionsGenerator::class.java).toSettings()
        }
        assertTrue(error.message!!.contains("(Options, CTestStructure, RandomProvider)"))
    }

    // Mirror generators compiled against the old API; only their constructor signatures matter.
    @Suppress("UNUSED_PARAMETER")
    internal class LegacyOptionsGenerator(options: Options<*, *>, structure: CTestStructure, random: RandomProvider) :
        ExecutionGenerator(LincheckSettings(), null) {
        override fun nextExecution() = throw UnsupportedOperationException()
    }

    class IncorrectValue {
        @Operation
        fun read() = 1
    }

    class SequentialValue {
        fun read() = 0
    }
}
