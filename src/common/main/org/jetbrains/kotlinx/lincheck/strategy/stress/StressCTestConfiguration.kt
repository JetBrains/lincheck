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
package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.verifier.*

/**
 * Configuration for [stress][StressStrategy] strategy.
 */
class StressCTestConfiguration(testClass: TestClass,
                               iterations: Int,
                               threads: Int,
                               actorsPerThread: Int,
                               actorsBefore: Int,
                               actorsAfter: Int,
                               executionGenerator: (testConfiguration: CTestConfiguration, testStructure: CTestStructure) -> ExecutionGenerator,
                               verifierGenerator: (sequentialSpecification: SequentialSpecification<*>) -> Verifier,
                               val invocationsPerIteration: Int,
                               requireStateEquivalenceCheck: Boolean,
                               minimizeFailedScenario: Boolean,
                               sequentialSpecification: SequentialSpecification<*>,
                               timeoutMs: Long,
                               customScenarios: List<ExecutionScenario>,
                               val initThreadFunction: (() -> Unit)? = null,
                               val finishThreadFunction: (() -> Unit)? = null
) : CTestConfiguration(testClass, iterations, threads, actorsPerThread, actorsBefore, actorsAfter, executionGenerator, verifierGenerator,
    requireStateEquivalenceCheck, minimizeFailedScenario, sequentialSpecification, timeoutMs, customScenarios) {
    override fun createStrategy(testClass: TestClass, scenario: ExecutionScenario, validationFunctions: List<ValidationFunction>,
                                stateRepresentationFunction: StateRepresentationFunction?, verifier: Verifier) =
        StressStrategy(this, testClass, scenario, validationFunctions, stateRepresentationFunction, verifier)

    companion object {
        const val DEFAULT_INVOCATIONS = 10000
    }
}
