/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*

class EventStructureCTestConfiguration(
        testClass: Class<*>, iterations: Int,
        threads: Int, actorsPerThread: Int, actorsBefore: Int, actorsAfter: Int,
        generatorClass: Class<out ExecutionGenerator>, verifierClass: Class<out Verifier>,
        checkObstructionFreedom: Boolean, hangingDetectionThreshold: Int, invocationsPerIteration: Int,
        guarantees: List<ManagedStrategyGuarantee>, requireStateEquivalenceCheck: Boolean, minimizeFailedScenario: Boolean,
        sequentialSpecification: Class<*>, timeoutMs: Long, eliminateLocalObjects: Boolean, verboseTrace: Boolean,
        customScenarios: List<ExecutionScenario>
) : ManagedCTestConfiguration(
        testClass, iterations,
        threads, actorsPerThread, actorsBefore, actorsAfter,
        generatorClass, verifierClass,
        checkObstructionFreedom, hangingDetectionThreshold, invocationsPerIteration,
        guarantees, requireStateEquivalenceCheck, minimizeFailedScenario,
        sequentialSpecification, timeoutMs, eliminateLocalObjects, verboseTrace,
        customScenarios
) {
    override fun createStrategy(testClass: Class<*>, scenario: ExecutionScenario, validationFunctions: List<Method>,
                                stateRepresentationMethod: Method?, verifier: Verifier): EventStructureStrategy
            = EventStructureStrategy(this, testClass, scenario, validationFunctions, stateRepresentationMethod, verifier)
}