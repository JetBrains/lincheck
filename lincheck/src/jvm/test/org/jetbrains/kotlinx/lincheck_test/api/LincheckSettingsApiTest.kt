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
import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy.ModelChecking.Algorithm
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.forClasses
import org.jetbrains.lincheck.datastructures.RandomProvider
import org.jetbrains.lincheck.datastructures.toSettings
import org.jetbrains.lincheck.datastructures.verifier.SerializabilityVerifier
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
        Lincheck.runConcurrentTest(copy, AtomicCounter::class)
    }

    @Test
    fun classAndKClassOverloadsSupportBothStrategies() {
        val settings = smallSettings()
        Lincheck.runConcurrentTest(settings, AtomicCounter::class.java)
        settings.strategy = TestingStrategy.Stress()
        Lincheck.runConcurrentTest(settings, AtomicCounter::class)
    }

    @Test
    fun settingsLambdaAndInvocationShorthand() {
        val settings = LincheckSettings {
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
        assertNull((settings.strategy as TestingStrategy.ModelChecking).algorithm)
        Lincheck.runConcurrentTest(10, block)
    }

    @Test
    fun kotlinBuildersConfigureNestedGroupsInPlace() {
        val settings = LincheckSettings {
            scenarioGeneration { threads = 3 }
            modelChecking { checkObstructionFreedom = true }
            modelChecking { loopDetection { loopBound = 100 } }
        }
        assertEquals(3, settings.scenarioGeneration.threads)
        val strategy = settings.strategy as TestingStrategy.ModelChecking
        assertTrue(strategy.checkObstructionFreedom)
        assertEquals(100, strategy.loopDetection.loopBound)
        settings.stress()
        assertTrue(settings.strategy is TestingStrategy.Stress)

        Lincheck.runConcurrentTest(LincheckSettings {
            modelChecking()
            smallScenarios()
        }, AtomicCounter::class)
        Lincheck.runConcurrentTest(LincheckSettings { modelChecking() }) { check(AtomicInteger(1).get() == 1) }
    }

    @Test
    fun legacyOptionsAndSettingsShareDefaults() {
        val settings = LincheckSettings()
        val strategy = TestingStrategy.ModelChecking()
        val legacy = ModelCheckingOptions().toSettings()
        val legacyStrategy = legacy.strategy as TestingStrategy.ModelChecking
        assertEquals(settings.timeoutMs, legacy.timeoutMs)
        assertEquals(settings.scenarioGeneration.iterations, legacy.scenarioGeneration.iterations)
        assertEquals(settings.scenarioGeneration.invocationsPerIteration, legacy.scenarioGeneration.invocationsPerIteration)
        assertEquals(strategy.analyzeStdLib, legacyStrategy.analyzeStdLib)
        assertEquals(strategy.checkObstructionFreedom, legacyStrategy.checkObstructionFreedom)
        assertEquals(strategy.loopDetection.loopBound, legacyStrategy.loopDetection.loopBound)
        assertEquals(strategy.loopDetection.recursionBound, legacyStrategy.loopDetection.recursionBound)
    }

    @Test
    fun lambdaFailureIsReportedByEveryStrategy() {
        val strategies = listOf(TestingStrategy.Stress()) +
            Algorithm.entries.map { TestingStrategy.ModelChecking().algorithm(it) }
        for (strategy in strategies) {
            val settings = LincheckSettings().strategy(strategy)
            val failure = assertThrows(LincheckAssertionError::class.java) {
                Lincheck.runConcurrentTest(settings) { error("public API failure") }
            }
            assertTrue(failure.message.orEmpty().contains("public API failure"))
        }
    }

    @Test
    fun stressIsDefaultWhileInvocationShorthandKeepsModelChecking() {
        assertTrue(LincheckSettings().strategy is TestingStrategy.Stress)
        assertTrue(LincheckSettings().copy().strategy is TestingStrategy.Stress)
        val stressRuns = AtomicInteger()
        Lincheck.runConcurrentTest(25, LincheckSettings()) { stressRuns.incrementAndGet() }
        assertEquals(25, stressRuns.get())
        // Unlike stress, model checking stops once it has explored this single-threaded block.
        val modelCheckingRuns = AtomicInteger()
        Lincheck.runConcurrentTest(25) { modelCheckingRuns.incrementAndGet() }
        assertTrue(modelCheckingRuns.get() < 25)
    }

    @Test
    fun stressLambdaRunsEveryInvocation() {
        val runs = AtomicInteger()
        Lincheck.runConcurrentTest(25, LincheckSettings().strategy(TestingStrategy.Stress())) {
            val counter = AtomicInteger()
            val first = thread { counter.incrementAndGet() }
            val second = thread { counter.incrementAndGet() }
            first.join()
            second.join()
            check(counter.get() == 2)
            runs.incrementAndGet()
        }
        assertEquals(25, runs.get())
    }

    @Test
    fun partialOrderReductionAndWeakMemorySelectEventStructure() {
        val settings = smallSettings()
        settings.strategy = TestingStrategy.ModelChecking().enablePartialOrderReduction()
        Lincheck.runConcurrentTest(settings, AtomicCounter::class)
        settings.strategy = TestingStrategy.ModelChecking().memoryModel(MemoryModel.ReleaseAcquire)
        Lincheck.runConcurrentTest(settings, AtomicCounter::class)
        Lincheck.runConcurrentTest(LincheckSettings().strategy(settings.strategy)) { check(AtomicInteger(1).get() == 1) }
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
    fun lambdaRejectsScenarioSettingsAndValidatesItsOwnInvocationLimit() {
        val modifications: List<Pair<String, LincheckSettings.ScenarioGeneration.() -> Unit>> = listOf(
            "iterations" to { iterations += 1 },
            "invocationsPerIteration" to { invocationsPerIteration += 1 },
            "threads" to { threads = 3 },
            "actorsPerThread" to { actorsPerThread = 1 },
            "actorsBefore" to { actorsBefore = 0 },
            "actorsAfter" to { actorsAfter = 0 },
            "executionGenerator" to { executionGenerator = SettingsGenerator::class.java },
            "customScenarios" to { addCustomScenario { parallel { thread { actor(AtomicCounter::increment) } } } },
            "minimizeFailedScenario" to { minimizeFailedScenario = !minimizeFailedScenario },
            "verifier" to { verifier = SerializabilityVerifier::class.java },
            "sequentialSpecification" to { sequentialSpecification = AtomicCounter::class.java },
        )
        for ((name, modify) in modifications) {
            val settings = LincheckSettings().strategy(TestingStrategy.ModelChecking())
            settings.scenarioGeneration.modify()
            val failure = assertThrows(IllegalArgumentException::class.java) {
                Lincheck.runConcurrentTest(10, settings) { fail("Must not execute") }
            }
            assertTrue(failure.message, failure.message.orEmpty().endsWith(": $name"))
        }

        val settings = LincheckSettings().strategy(TestingStrategy.ModelChecking())
        settings.scenarioGeneration.threads = 3
        settings.scenarioGeneration = LincheckSettings.ScenarioGeneration()
        Lincheck.runConcurrentTest(10, settings) { check(AtomicInteger(1).get() == 1) }
        assertThrows(IllegalArgumentException::class.java) {
            Lincheck.runConcurrentTest(0, settings) { fail("Must not execute") }
        }
        Lincheck.runConcurrentTest(10, settings.strategy(TestingStrategy.Stress())) { check(AtomicInteger(1).get() == 1) }
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
        Lincheck.runConcurrentTest(settings, AtomicCounter::class)
        Lincheck.runConcurrentTest(settings, AtomicCounter::class)
        assertNull(settings.scenarioGeneration.customScenarios.single().validationFunction)
    }

    @Test
    fun customGeneratorReceivesSettings() {
        val settings = smallSettings().apply {
            scenarioGeneration.executionGenerator = SettingsGenerator::class.java
        }
        Lincheck.runConcurrentTest(settings, AtomicCounter::class)
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
        modelChecking()
        smallScenarios()
    }

    private fun LincheckSettings.smallScenarios() = scenarioGeneration {
        invocationsPerIteration = 20
        iterations = 2
        actorsBefore = 0
        actorsAfter = 0
        actorsPerThread = 1
    }
}
