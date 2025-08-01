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
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressStrategy
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.STRESS
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import java.lang.reflect.Method

/**
 * Options for the stress strategy.
 */
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
        return StressStrategy(testClass, scenario, validationFunction, stateRepresentationMethod, this.timeoutMs)
    }
}
