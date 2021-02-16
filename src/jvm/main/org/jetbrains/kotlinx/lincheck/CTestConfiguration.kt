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
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_ELIMINATE_LOCAL_OBJECTS
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_VERBOSE_TRACE
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*
import kotlin.reflect.*

actual val DEFAULT_EXECUTION_GENERATOR: ExecutionGeneratorClass<out ExecutionGenerator> = RandomExecutionGenerator::class.java
actual val DEFAULT_VERIFIER: VerifierClass<out Verifier> = LinearizabilityVerifier::class.java

internal fun createFromTestClassAnnotations(testClass: Class<*>): List<CTestConfiguration> {
    val stressConfigurations: List<CTestConfiguration> = testClass.getAnnotationsByType(StressCTest::class.java)
        .map { ann: StressCTest ->
            StressCTestConfiguration(TestClass(testClass), ann.iterations,
                ann.threads, ann.actorsPerThread, ann.actorsBefore, ann.actorsAfter,
                ann.generator.java, { sequentialSpecification ->
                    ann.verifier.java.getConstructor(SequentialSpecification::class.java).newInstance(sequentialSpecification)
                }, ann.invocationsPerIteration,
                ann.requireStateEquivalenceImplCheck, ann.minimizeFailedScenario,
                chooseSequentialSpecification(ann.sequentialSpecification.java, TestClass(testClass)), DEFAULT_TIMEOUT_MS
            )
        }
    val modelCheckingConfigurations: List<CTestConfiguration> = testClass.getAnnotationsByType(ModelCheckingCTest::class.java)
        .map { ann: ModelCheckingCTest ->
            ModelCheckingCTestConfiguration(TestClass(testClass), ann.iterations,
                ann.threads, ann.actorsPerThread, ann.actorsBefore, ann.actorsAfter,
                ann.generator.java, { sequentialSpecification ->
                    ann.verifier.java.getConstructor(SequentialSpecification::class.java).newInstance(sequentialSpecification)
                }, ann.checkObstructionFreedom, ann.hangingDetectionThreshold,
                ann.invocationsPerIteration, ManagedCTestConfiguration.DEFAULT_GUARANTEES, ann.requireStateEquivalenceImplCheck,
                ann.minimizeFailedScenario, chooseSequentialSpecification(ann.sequentialSpecification.java, TestClass(testClass)),
                DEFAULT_TIMEOUT_MS, DEFAULT_ELIMINATE_LOCAL_OBJECTS, DEFAULT_VERBOSE_TRACE
            )
        }
    return stressConfigurations + modelCheckingConfigurations
}