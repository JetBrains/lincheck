package org.jetbrains.kotlinx.lincheck.distributed

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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

import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.distributed.stress.DistributedStrategy
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method
import java.util.*


class DistributedCTestConfiguration<Message, Log>(testClass: Class<*>, iterations: Int,
                                                  threads: Int, actorsPerThread: Int,
                                                  generatorClass: Class<out ExecutionGenerator>,
                                                  verifierClass: Class<out Verifier>,
                                                  val invocationsPerIteration: Int,
                                                  val isNetworkReliable: Boolean,
                                                  val messageOrder: MessageOrder,
                                                  val maxNumberOfFailedNodes: (Int) -> Int,
                                                  val supportRecovery: Boolean,
                                                  val messageDuplication: Boolean,
                                                  val networkPartitions : Boolean,
                                                  val asyncRun : Boolean,
                                                  val nodeTypes: HashMap<Class<out Node<Message>>, Int>,
                                                  requireStateEquivalenceCheck: Boolean,
                                                  minimizeFailedScenario: Boolean,
                                                  sequentialSpecification: Class<*>, timeoutMs: Long) :
        CTestConfiguration(testClass, iterations, threads, actorsPerThread,
                0, 0, generatorClass, verifierClass,
                requireStateEquivalenceCheck,
                minimizeFailedScenario, sequentialSpecification, timeoutMs) {

    companion object {
        const val DEFAULT_INVOCATIONS = 10000
    }

    override fun createStrategy(testClass: Class<*>, scenario: ExecutionScenario, validationFunctions: List<Method>, stateRepresentationMethod: Method?, verifier: Verifier): Strategy {
        return DistributedStrategy(this, testClass, scenario, validationFunctions, stateRepresentationMethod, verifier)
    }
}
