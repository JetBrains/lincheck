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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*

/**
 * Configuration for [stress][StressStrategy] strategy.
 */
class StressCTestConfiguration(
    testClass: Class<*>, iterations: Int, threads: Int, actorsPerThread: Int, actorsBefore: Int, actorsAfter: Int,
    generatorClass: Class<out ExecutionGenerator>, verifierClass: Class<out Verifier>,
    val invocationsPerIteration: Int, minimizeFailedScenario: Boolean,
    sequentialSpecification: Class<*>, timeoutMs: Long, customScenarios: List<ExecutionScenario>
) : CTestConfiguration(
    testClass = testClass,
    iterations = iterations,
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
    override fun createStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunction: Actor?,
        stateRepresentationMethod: Method?
    ) = StressStrategy(this, testClass, scenario, validationFunction, stateRepresentationMethod)

    companion object {
        const val DEFAULT_INVOCATIONS = 10000
    }
}
