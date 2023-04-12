/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.CTestConfiguration.Companion.DEFAULT_TIMEOUT_MS
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_ELIMINATE_LOCAL_OBJECTS
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
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
    abstract fun createStrategy(
        testClass: Class<*>, scenario: ExecutionScenario, validationFunctions: List<Method>,
        stateRepresentationMethod: Method?, verifier: Verifier
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
        const val DEFAULT_TIMEOUT_MS: Long = 10000
    }
}

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
                    eliminateLocalObjects = DEFAULT_ELIMINATE_LOCAL_OBJECTS,
                    customScenarios = emptyList()
                )
            }
    return stressConfigurations + modelCheckingConfigurations
}