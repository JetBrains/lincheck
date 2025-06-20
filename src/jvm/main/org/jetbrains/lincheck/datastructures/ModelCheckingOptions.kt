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
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.MODEL_CHECKING
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import java.lang.reflect.Method

/**
 * Options for the model checking strategy.
 */
class ModelCheckingOptions : ManagedOptions<ModelCheckingOptions, ModelCheckingCTestConfiguration>() {
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
            hangingDetectionThreshold = hangingDetectionThreshold,
            invocationsPerIteration = invocationsPerIteration,
            guarantees = guarantees,
            minimizeFailedScenario = minimizeFailedScenario,
            sequentialSpecification = chooseSequentialSpecification(sequentialSpecification, testClass),
            timeoutMs = timeoutMs,
            customScenarios = customScenarios,
            stdLibAnalysisEnabled = stdLibAnalysisEnabled,
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
    hangingDetectionThreshold: Int,
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
    hangingDetectionThreshold = hangingDetectionThreshold,
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
    ): Strategy = ModelCheckingStrategy(
        testClass,
        scenario,
        validationFunction,
        stateRepresentationMethod,
        createSettings()
    )
}