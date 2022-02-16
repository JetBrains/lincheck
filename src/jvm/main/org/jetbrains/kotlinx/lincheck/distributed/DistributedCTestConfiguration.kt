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
import org.jetbrains.kotlinx.lincheck.distributed.event.EventFormatter
import org.jetbrains.kotlinx.lincheck.distributed.event.TextEventFormatter
import org.jetbrains.kotlinx.lincheck.distributed.random.DistributedRandomStrategy
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method


class DistributedCTestConfiguration<Message>(
    testClass: Class<*>, iterations: Int,
    threads: Int, actorsPerThread: Int,
    generatorClass: Class<out ExecutionGenerator>,
    verifierClass: Class<out Verifier>,
    val invocationsPerIteration: Int,
    val messageLoss: Boolean,
    val messageOrder: MessageOrder,
    val messageDuplication: Boolean,
    private val nodeTypes: Map<Class<out Node<Message>>, NodeTypeInfo>,
    val logFilename: String?,
    val crashNotifications: Boolean,
    requireStateEquivalenceCheck: Boolean,
    minimizeFailedScenario: Boolean,
    sequentialSpecification: Class<*>, timeoutMs: Long,
    customScenarios: List<ExecutionScenario>
) :
    CTestConfiguration(
        testClass, iterations, threads, actorsPerThread,
        0, 0, generatorClass, verifierClass,
        requireStateEquivalenceCheck,
        minimizeFailedScenario, sequentialSpecification, timeoutMs,
        customScenarios
    ) {
    var addressResolver: NodeAddressResolver<Message> = NodeAddressResolver(
        testClass as Class<out Node<Message>>,
        nodeTypes[testClass]?.nodes ?: threads,
        nodeTypes
    )

    companion object {
        const val DEFAULT_INVOCATIONS = 10000
    }

    override fun createStrategy(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunctions: List<Method>,
        stateRepresentationMethod: Method?,
        verifier: Verifier
    ): Strategy {
        addressResolver = NodeAddressResolver(
            testClass as Class<out Node<Message>>,
            scenario.threads, nodeTypes
        )
        return DistributedRandomStrategy(
            this,
            testClass,
            scenario,
            validationFunctions,
            verifier
        ).also { it.initialize() }
    }

    /**
     *
     */
    fun nextConfigurations(scenario: ExecutionScenario): Iterable<DistributedCTestConfiguration<Message>> = Iterable {
        iterator {
            for ((cls, nodeInfo) in nodeTypes) {
                if (nodeInfo.nodes == nodeInfo.minNumberOfInstances || cls == testClass && nodeInfo.nodes == scenario.threads) {
                    continue
                }
                val newNodeTypes = nodeTypes.toMutableMap()
                newNodeTypes[cls] = nodeInfo.minimize()
                yield(
                    DistributedCTestConfiguration(
                        testClass, iterations,
                        threads, actorsPerThread,
                        generatorClass,
                        verifierClass,
                        invocationsPerIteration,
                        messageLoss,
                        messageOrder,
                        messageDuplication,
                        newNodeTypes, logFilename, crashNotifications, requireStateEquivalenceImplCheck,
                        minimizeFailedScenario,
                        sequentialSpecification, timeoutMs, customScenarios
                    )
                )
            }
        }
    }


    internal fun getFormatter(): EventFormatter = TextEventFormatter(addressResolver)
}
