/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*

/**
 * Configuration for [random search][ModelCheckingStrategy] strategy.
 */
class ModelCheckingCTestConfiguration(testClass: Class<*>, iterations: Int, threads: Int, actorsPerThread: Int, actorsBefore: Int,
                                      actorsAfter: Int, generatorClass: Class<out ExecutionGenerator>, verifierClass: Class<out Verifier>,
                                      checkObstructionFreedom: Boolean, hangingDetectionThreshold: Int, invocationsPerIteration: Int,
                                      guarantees: List<ManagedStrategyGuarantee>, minimizeFailedScenario: Boolean,
                                      sequentialSpecification: Class<*>, timeoutMs: Long, eliminateLocalObjects: Boolean,
                                      customScenarios: List<ExecutionScenario>
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
    eliminateLocalObjects = eliminateLocalObjects,
    customScenarios = customScenarios
) {
    override fun createStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunction: Actor?,
        stateRepresentationMethod: Method?,
    ): Strategy = ModelCheckingStrategy(this, testClass, scenario, validationFunction, stateRepresentationMethod)
}
