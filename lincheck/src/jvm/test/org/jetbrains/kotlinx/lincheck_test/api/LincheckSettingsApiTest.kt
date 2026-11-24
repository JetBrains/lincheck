/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.api

import org.jetbrains.lincheck.datastructures.Validate
import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.RandomExecutionGenerator
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy.ModelChecking.Algorithm
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.forClasses
import org.jetbrains.lincheck.datastructures.RandomProvider
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LincheckSettingsApiTest {
    @Test
    fun copyKeepsNestedConfigurationIndependentAndAlgorithmAutomatic() {
        val original = smallSettings()
        val copy = original.copy()
        copy.scenarioGeneration.threads = 3
        val strategy = copy.strategy as TestingStrategy.ModelChecking
        strategy.loopDetection.loopBound = 200
        strategy.enablePartialOrderReduction()
        assertEquals(2, original.scenarioGeneration.threads)
        assertEquals(50, (original.strategy as TestingStrategy.ModelChecking).loopDetection.loopBound)
        assertNull(strategy.algorithm)
        Lincheck.runLincheckTest(copy, AtomicCounter::class)
    }

    @Test
    fun classAndKClassOverloadsSupportBothStrategies() {
        val settings = smallSettings()
        Lincheck.runLincheckTest(settings, AtomicCounter::class.java)
        settings.strategy = TestingStrategy.Stress()
        Lincheck.runLincheckTest(settings, AtomicCounter::class)
    }

    @Test
    fun settingsLambdaAndInvocationShorthand() {
        val settings = LincheckSettings {
            scenarioGeneration.invocationsPerIteration = 20
            strategy = TestingStrategy.ModelChecking().analyzeStdLib(true)
        }
        val block = Runnable {
            val counter = AtomicInteger()
            val first = thread { counter.incrementAndGet() }
            val second = thread { counter.incrementAndGet() }
            first.join()
            second.join()
            check(counter.get() == 2)
        }
        Lincheck.runConcurrentTest(settings, block)
        Lincheck.runConcurrentTest(10, settings, block)
        assertEquals(20, settings.scenarioGeneration.invocationsPerIteration)
        Lincheck.runConcurrentTest(10, block)
    }

    @Test
    fun lambdaFailureIsReportedByBothAlgorithms() {
        for (algorithm in Algorithm.entries) {
            val settings = LincheckSettings().strategy(TestingStrategy.ModelChecking().algorithm(algorithm))
            val failure = assertThrows(LincheckAssertionError::class.java) {
                Lincheck.runConcurrentTest(settings) { error("public API failure") }
            }
            assertTrue(failure.message.orEmpty().contains("public API failure"))
        }
    }

    @Test
    fun partialOrderReductionAndWeakMemorySelectEventStructure() {
        val settings = smallSettings()
        settings.strategy = TestingStrategy.ModelChecking().enablePartialOrderReduction()
        Lincheck.runLincheckTest(settings, AtomicCounter::class)
        settings.strategy = TestingStrategy.ModelChecking().memoryModel(MemoryModel.ReleaseAcquire)
        Lincheck.runLincheckTest(settings, AtomicCounter::class)
        Lincheck.runConcurrentTest(settings) { check(AtomicInteger(1).get() == 1) }
    }

    @Test
    fun incompatibleAlgorithmIsRejectedRegardlessOfConfigurationOrder() {
        for (strategy in listOf(
            TestingStrategy.ModelChecking().enablePartialOrderReduction().algorithm(Algorithm.InterleavingTree),
            TestingStrategy.ModelChecking().algorithm(Algorithm.InterleavingTree).enablePartialOrderReduction(),
            TestingStrategy.ModelChecking().memoryModel(MemoryModel.ReleaseAcquire).algorithm(Algorithm.InterleavingTree),
            TestingStrategy.ModelChecking().algorithm(Algorithm.InterleavingTree).memoryModel(MemoryModel.ReleaseAcquire),
        )) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                Lincheck.runConcurrentTest(LincheckSettings().strategy(strategy)) { fail("Must not execute") }
            }
            assertTrue(failure.message, failure.message.orEmpty().contains("Event Structure"))
        }
    }

    @Test
    fun lambdaIgnoresScenarioSettingsAndValidatesItsOwnInvocationLimit() {
        val settings = smallSettings().apply {
            scenarioGeneration.invocationsPerIteration = 1
            scenarioGeneration.sequentialSpecification = AtomicCounter::class.java
        }
        Lincheck.runConcurrentTest(10, settings) { check(AtomicInteger(1).get() == 1) }
        Lincheck.runConcurrentTest(settings) { check(AtomicInteger(1).get() == 1) }
        assertEquals(1, settings.scenarioGeneration.invocationsPerIteration)
        assertThrows(IllegalArgumentException::class.java) {
            Lincheck.runConcurrentTest(0, settings) { fail("Must not execute") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Lincheck.runConcurrentTest(settings.strategy(TestingStrategy.Stress())) { fail("Must not execute") }
        }
    }

    @Test
    fun propertySettersAndBuilderMethodsRejectInvalidValuesUniformly() {
        val settings = LincheckSettings()
        val generation = settings.scenarioGeneration
        val loopDetection = LincheckSettings.LoopDetection()
        val invalidAssignments: List<Pair<() -> Unit, () -> Unit>> = listOf(
            { settings.timeoutMs = 0 } to { settings.timeoutMs(0) },
            { generation.iterations = -1 } to { generation.iterations(-1) },
            { generation.invocationsPerIteration = 0 } to { generation.invocationsPerIteration(0) },
            { generation.threads = 0 } to { generation.threads(0) },
            { generation.actorsPerThread = 0 } to { generation.actorsPerThread(0) },
            { generation.actorsBefore = -1 } to { generation.actorsBefore(-1) },
            { generation.actorsAfter = -1 } to { generation.actorsAfter(-1) },
            { loopDetection.loopIterationsBeforeThreadSwitch = 0 } to { loopDetection.loopIterationsBeforeThreadSwitch(0) },
            { loopDetection.loopBound = 0 } to { loopDetection.loopBound(0) },
            { loopDetection.recursionBound = 0 } to { loopDetection.recursionBound(0) },
        )
        for ((assignProperty, callBuilder) in invalidAssignments) {
            assertThrows(IllegalArgumentException::class.java) { assignProperty() }
            assertThrows(IllegalArgumentException::class.java) { callBuilder() }
        }
        val defaults = LincheckSettings()
        assertEquals(defaults.timeoutMs, settings.timeoutMs)
        assertEquals(defaults.scenarioGeneration.iterations, generation.iterations)
        assertEquals(LincheckSettings.LoopDetection().loopBound, loopDetection.loopBound)
        generation.iterations = 0
        generation.actorsBefore = 0
        generation.actorsAfter = 0
        assertEquals(0, generation.iterations)
    }

    @Test
    fun clearMethodsRemoveAllListEntries() {
        val generation = LincheckSettings.ScenarioGeneration()
            .addCustomScenario { parallel { thread { actor(AtomicCounter::increment) } } }
            .addCustomScenario { parallel { thread { actor(AtomicCounter::increment) }; thread { actor(AtomicCounter::increment) } } }
        assertEquals(2, generation.customScenarios.size)
        assertSame(generation, generation.removeCustomScenarios())
        assertTrue(generation.customScenarios.isEmpty())

        val strategy = TestingStrategy.ModelChecking()
            .addGuarantee(forClasses(AtomicCounter::class).allMethods().treatAsAtomic())
        assertEquals(1, strategy.guarantees.size)
        assertSame(strategy, strategy.removeGuarantees())
        assertTrue(strategy.guarantees.isEmpty())
    }

    @Test
    fun customScenarioIsReusableWithoutMutatingItsValidationFunction() {
        val settings = smallSettings().apply {
            scenarioGeneration.iterations = 0
            scenarioGeneration.addCustomScenario {
                parallel {
                    thread { actor(AtomicCounter::increment) }
                    thread { actor(AtomicCounter::increment) }
                }
            }
        }
        Lincheck.runLincheckTest(settings, AtomicCounter::class)
        Lincheck.runLincheckTest(settings, AtomicCounter::class)
        assertNull(settings.scenarioGeneration.customScenarios.single().validationFunction)
    }

    @Test
    fun customGeneratorReceivesSettings() {
        val settings = smallSettings().apply {
            scenarioGeneration.executionGenerator = SettingsGenerator::class.java
        }
        Lincheck.runLincheckTest(settings, AtomicCounter::class)
    }

    class SettingsGenerator(settings: LincheckSettings, structure: CTestStructure, random: RandomProvider) :
        ExecutionGenerator(settings, structure) {
        private val delegate = RandomExecutionGenerator(settings, structure, random)
        override fun nextExecution() = delegate.nextExecution()
    }

    class AtomicCounter {
        private val value = AtomicInteger()
        @Operation
        fun increment() = value.incrementAndGet()
        @Validate
        fun validate() { check(value.get() >= 0) }
    }

    private fun smallSettings() = LincheckSettings {
        scenarioGeneration.invocationsPerIteration = 20
        scenarioGeneration.apply {
            iterations = 2
            actorsBefore = 0
            actorsAfter = 0
            actorsPerThread = 1
        }
    }
}
