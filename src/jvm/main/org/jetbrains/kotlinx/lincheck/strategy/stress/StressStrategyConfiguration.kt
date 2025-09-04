/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.lincheck.datastructures.CTestConfiguration
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.ExecutionScenarioRunner
import org.jetbrains.kotlinx.lincheck.runner.UseClocks
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode
import org.jetbrains.lincheck.jvm.agent.InstrumentationMode.STRESS
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import java.lang.reflect.Method

/**
 * Options for the stress strategy.
 */
@Deprecated(
    level = DeprecationLevel.WARNING,
    message = "Use org.jetbrains.lincheck.datastructures.StressOptions instead.",
)
@Suppress("DEPRECATION")
open class StressOptions : Options<StressOptions, StressCTestConfiguration>() {
    override fun createTestConfigurations(testClass: Class<*>): StressCTestConfiguration {
        return StressCTestConfiguration(
            testClass = testClass,
            iterations = iterations,
            threads = threads,
            actorsPerThread = actorsPerThread,
            actorsBefore = actorsBefore,
            actorsAfter = actorsAfter,
            generatorClass = executionGenerator,
            verifierClass = verifier,
            invocationsPerIteration = invocationsPerIteration,
            minimizeFailedScenario = minimizeFailedScenario,
            sequentialSpecification = chooseSequentialSpecification(sequentialSpecification, testClass),
            timeoutMs = timeoutMs,
            customScenarios = customScenarios
        )
    }
}

/**
 * Configuration for the stress strategy.
 */
class StressCTestConfiguration(
    testClass: Class<*>,
    iterations: Int,
    threads: Int,
    actorsPerThread: Int,
    actorsBefore: Int,
    actorsAfter: Int,
    generatorClass: Class<out ExecutionGenerator>,
    verifierClass: Class<out Verifier>,
    invocationsPerIteration: Int,
    minimizeFailedScenario: Boolean,
    sequentialSpecification: Class<*>,
    timeoutMs: Long,
    customScenarios: List<ExecutionScenario>
) : CTestConfiguration(
    testClass = testClass,
    iterations = iterations,
    invocationsPerIteration = invocationsPerIteration,
    threads = threads,
    actorsPerThread = actorsPerThread,
    actorsBefore = actorsBefore,
    actorsAfter = actorsAfter,
    generatorClass = generatorClass,
    verifierClass = verifierClass,
    minimizeFailedScenario = minimizeFailedScenario,
    sequentialSpecification = sequentialSpecification,
    timeoutMs = timeoutMs,
    customScenarios = customScenarios
) {

    override val instrumentationMode: InstrumentationMode get() = STRESS

    override fun createStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunction: Actor?,
        stateRepresentationMethod: Method?
    ): Strategy {
        val runner = ExecutionScenarioRunner(
            scenario = scenario,
            testClass = testClass,
            validationFunction = validationFunction,
            stateRepresentationFunction = stateRepresentationMethod,
            timeoutMs = timeoutMs,
            useClocks = UseClocks.RANDOM
        )
        return StressStrategy(runner).also {
            runner.initializeStrategy(it)
        }
    }
}
