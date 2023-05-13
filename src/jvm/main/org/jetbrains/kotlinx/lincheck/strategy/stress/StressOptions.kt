/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification

/**
 * Options for [stress][StressStrategy] strategy.
 */
open class StressOptions : Options<StressOptions, StressCTestConfiguration>() {
    private var invocationsPerIteration = StressCTestConfiguration.DEFAULT_INVOCATIONS

    /**
     * Run each test scenario the specified number of times.
     */
    fun invocationsPerIteration(invocations: Int): StressOptions = apply {
        invocationsPerIteration = invocations
    }

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
