/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.ExecutionScenarioRunner
import org.jetbrains.kotlinx.lincheck.runner.UseClocks
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.lincheck.datastructures.ManagedCTestConfiguration
import org.jetbrains.lincheck.datastructures.ManagedOptions
import org.jetbrains.lincheck.datastructures.ManagedStrategyGuarantee
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode.MODEL_CHECKING
import org.jetbrains.lincheck.datastructures.getTimeOutMs
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import java.lang.reflect.Method

/**
 * Options for the model checking strategy.
 */
@Deprecated(
    level = DeprecationLevel.ERROR,
    message = "Use org.jetbrains.lincheck.datastructures.ModelCheckingOptions instead.",
)
@Suppress("DEPRECATION")
class ModelCheckingOptions : ManagedOptions<
    org.jetbrains.lincheck.datastructures.ModelCheckingOptions,
    org.jetbrains.lincheck.datastructures.ModelCheckingCTestConfiguration
>() {
    override fun createTestConfigurations(testClass: Class<*>): org.jetbrains.lincheck.datastructures.ModelCheckingCTestConfiguration {
        return org.jetbrains.lincheck.datastructures.ModelCheckingCTestConfiguration(
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
            sequentialSpecification = chooseSequentialSpecification(sequentialSpecification, testClass),
            timeoutMs = timeoutMs,
            customScenarios = customScenarios,
            stdLibAnalysisEnabled = stdLibAnalysisEnabled,
            experimentalModelChecking = false,
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
) {

    override val instrumentationMode: InstrumentationMode get() = MODEL_CHECKING

    override fun createStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunction: Actor?,
        stateRepresentationMethod: Method?,
    ): Strategy {
        val runner = ExecutionScenarioRunner(
            scenario = scenario,
            testClass = testClass,
            validationFunction = validationFunction,
            stateRepresentationFunction = stateRepresentationMethod,
            timeoutMs = getTimeOutMs(inIdeaPluginReplayMode, timeoutMs),
            useClocks = UseClocks.ALWAYS
        )
        return ModelCheckingStrategy(runner, createSettings(), inIdeaPluginReplayMode, LincheckInstrumentation.context).also {
            runner.initializeStrategy(it)
        }
    }
}