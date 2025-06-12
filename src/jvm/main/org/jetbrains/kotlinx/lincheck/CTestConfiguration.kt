/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.CTestConfiguration.Companion.DEFAULT_TIMEOUT_MS
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*
import java.lang.reflect.*

/**
 * Abstract configuration for different lincheck modes.
 */
abstract class CTestConfiguration(
    val testClass: Class<*>,
    val iterations: Int,
    val threads: Int,
    val actorsPerThread: Int,
    val actorsBefore: Int,
    val actorsAfter: Int,
    val generatorClass: Class<out ExecutionGenerator>,
    val verifierClass: Class<out Verifier>,
    val minimizeFailedScenario: Boolean,
    val sequentialSpecification: Class<*>,
    val timeoutMs: Long,
    val customScenarios: List<ExecutionScenario>
) {

    /**
     * Specifies the transformation required for this strategy.
     */
    internal abstract val instrumentationMode: InstrumentationMode

    abstract fun createStrategy(
        testClass: Class<*>, scenario: ExecutionScenario, validationFunction: Actor?,
        stateRepresentationMethod: Method?
    ): Strategy

    companion object {
        const val DEFAULT_ITERATIONS = 100
        const val DEFAULT_THREADS = 2
        const val DEFAULT_ACTORS_PER_THREAD = 5
        const val DEFAULT_ACTORS_BEFORE = 5
        const val DEFAULT_ACTORS_AFTER = 5
        val DEFAULT_EXECUTION_GENERATOR: Class<out ExecutionGenerator?> = RandomExecutionGenerator::class.java
        val DEFAULT_VERIFIER: Class<out Verifier> = LinearizabilityVerifier::class.java
        const val DEFAULT_MINIMIZE_ERROR = true
        const val DEFAULT_TIMEOUT_MS: Long = 3_000_000 // 30 sec
    }
}

internal fun CTestConfiguration.createVerifier() = verifierClass.getConstructor(Class::class.java).newInstance(sequentialSpecification)

internal fun createFromTestClassAnnotations(testClass: Class<*>): List<CTestConfiguration> {
    val stressConfigurations: List<CTestConfiguration> = testClass.getAnnotationsByType(StressCTest::class.java)
        .map { ann: StressCTest ->
            StressCTestConfiguration(
                testClass = testClass,
                iterations = ann.iterations,
                threads = ann.threads,
                actorsPerThread = ann.actorsPerThread,
                actorsBefore = ann.actorsBefore,
                actorsAfter = ann.actorsAfter,
                generatorClass = ann.generator.java,
                verifierClass = ann.verifier.java,
                invocationsPerIteration = ann.invocationsPerIteration,
                minimizeFailedScenario = ann.minimizeFailedScenario,
                sequentialSpecification = chooseSequentialSpecification(ann.sequentialSpecification.java, testClass),
                timeoutMs = DEFAULT_TIMEOUT_MS,
                customScenarios = emptyList()
            )
        }
    val modelCheckingConfigurations: List<CTestConfiguration> =
        testClass.getAnnotationsByType(ModelCheckingCTest::class.java)
            .map { ann: ModelCheckingCTest ->
                ModelCheckingCTestConfiguration(
                    testClass = testClass,
                    iterations = ann.iterations,
                    threads = ann.threads,
                    actorsPerThread = ann.actorsPerThread,
                    actorsBefore = ann.actorsBefore,
                    actorsAfter = ann.actorsAfter,
                    generatorClass = ann.generator.java,
                    verifierClass = ann.verifier.java,
                    checkObstructionFreedom = ann.checkObstructionFreedom,
                    hangingDetectionThreshold = ann.hangingDetectionThreshold,
                    invocationsPerIteration = ann.invocationsPerIteration,
                    guarantees = ManagedCTestConfiguration.DEFAULT_GUARANTEES,
                    minimizeFailedScenario = ann.minimizeFailedScenario,
                    sequentialSpecification = chooseSequentialSpecification(
                        ann.sequentialSpecification.java,
                        testClass
                    ),
                    timeoutMs = DEFAULT_TIMEOUT_MS,
                    customScenarios = emptyList(),
                    stdLibAnalysisEnabled = false,
                )
            }
    return stressConfigurations + modelCheckingConfigurations
}