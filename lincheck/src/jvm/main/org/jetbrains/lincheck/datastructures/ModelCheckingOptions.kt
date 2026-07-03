/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.datastructures

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.ExecutionScenarioRunner
import org.jetbrains.kotlinx.lincheck.runner.UseClocks
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.EventStructureStrategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode.MODEL_CHECKING
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation

/**
 * Options for the model checking strategy.
 */
class ModelCheckingOptions : ManagedOptions<ModelCheckingOptions, ModelCheckingCTestConfiguration>() {

    private var experimentalModelChecking = false
    private var memoryModel: MemoryModel = MemoryModel.SequentialConsistency

    internal fun useExperimentalModelChecking(): ModelCheckingOptions {
        experimentalModelChecking = true
        return this
    }

    val isExperimentalModelCheckingEnabled: Boolean
        get() = experimentalModelChecking


    internal fun memoryModel(model: MemoryModel): ModelCheckingOptions {
        check(experimentalModelChecking) { "You need to set experimentalModelChecking to use this feature" }
        memoryModel = model
        return this
    }

    override fun createTestConfigurations(testClass: Class<*>): ModelCheckingCTestConfiguration {
        return ModelCheckingCTestConfiguration(
            testClass = testClass,
            iterations = iterations,
            threads = threads,
            actorsPerThread = actorsPerThread,
            actorsBefore = actorsBefore,
            actorsAfter = actorsAfter,
            generatorClass = executionGenerator,
            verifierClass = verifier,
            checkObstructionFreedom = checkObstructionFreedom,
            loopIterationsBeforeThreadSwitch = loopIterationsBeforeThreadSwitch,
            loopBound = loopBound,
            recursionBound = recursionBound,
            invocationsPerIteration = invocationsPerIteration,
            guarantees = guarantees,
            minimizeFailedScenario = minimizeFailedScenario,
            sequentialSpecification = sequentialSpecification ?: testClass,
            timeoutMs = timeoutMs,
            customScenarios = customScenarios,
            stdLibAnalysisEnabled = stdLibAnalysisEnabled,
            awaitLoopsAnalysisEnabled = awaitLoopsAnalysisEnabled,
            experimentalModelChecking = experimentalModelChecking,
        )
    }
}

/**
 * Configuration for the model checking strategy.
 */
class ModelCheckingCTestConfiguration(
    testClass: Class<*>,
    iterations: Int,
    threads: Int,
    actorsPerThread: Int,
    actorsBefore: Int,
    actorsAfter: Int,
    generatorClass: Class<out ExecutionGenerator>,
    verifierClass: Class<out Verifier>,
    checkObstructionFreedom: Boolean,
    loopIterationsBeforeThreadSwitch: Int,
    loopBound: Int,
    recursionBound: Int,
    invocationsPerIteration: Int,
    guarantees: List<ManagedStrategyGuarantee>,
    minimizeFailedScenario: Boolean,
    sequentialSpecification: Class<*>,
    timeoutMs: Long,
    customScenarios: List<ExecutionScenario>,
    stdLibAnalysisEnabled: Boolean,
    awaitLoopsAnalysisEnabled: Boolean,
    experimentalModelChecking: Boolean,
) : ManagedCTestConfiguration(
    testClass = testClass,
    iterations = iterations,
    threads = threads,
    actorsPerThread = actorsPerThread,
    actorsBefore = actorsBefore,
    actorsAfter = actorsAfter,
    generatorClass = generatorClass,
    verifierClass = verifierClass,
    checkObstructionFreedom = checkObstructionFreedom,
    loopIterationsBeforeThreadSwitch = loopIterationsBeforeThreadSwitch,
    loopBound = loopBound,
    recursionBound = recursionBound,
    invocationsPerIteration = invocationsPerIteration,
    guarantees = guarantees,
    minimizeFailedScenario = minimizeFailedScenario,
    sequentialSpecification = sequentialSpecification,
    timeoutMs = timeoutMs,
    customScenarios = customScenarios,
    stdLibAnalysisEnabled = stdLibAnalysisEnabled,
    awaitLoopsAnalysisEnabled = awaitLoopsAnalysisEnabled,
) {

    private val useExperimentalModelChecking =
        experimentalModelChecking || System.getProperty("lincheck.useExperimentalModelChecking")?.toBoolean() ?: false

    override val instrumentationMode: InstrumentationMode get() =
        if (useExperimentalModelChecking) InstrumentationMode.EXPERIMENTAL_MODEL_CHECKING else MODEL_CHECKING

    override fun createStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunction: Actor?,
    ): Strategy {
        val runner = ExecutionScenarioRunner(
            scenario = scenario,
            testClass = testClass,
            validationFunction = validationFunction,
            timeoutMs = getTimeOutMs(inIdeaPluginReplayMode, timeoutMs),
            useClocks = UseClocks.ALWAYS
        )
        if (useExperimentalModelChecking) {
            return EventStructureStrategy(runner, createSettings(), inIdeaPluginReplayMode, LincheckInstrumentation.context).also {
                runner.initializeStrategy(it)
            }
        } else {
            return ModelCheckingStrategy(runner, createSettings(), inIdeaPluginReplayMode, LincheckInstrumentation.context).also {
                runner.initializeStrategy(it)
            }
        }
    }
}

internal fun getTimeOutMs(inIdeaPluginReplayMode: Boolean, defaultTimeOutMs: Long): Long =
    if (inIdeaPluginReplayMode) INFINITE_TIMEOUT else defaultTimeOutMs

/**
 * With idea plugin enabled, we should not use default Lincheck timeout
 * as debugging may take more time than default timeout.
 */
private const val INFINITE_TIMEOUT = 1000L * 60 * 60 * 24 * 365