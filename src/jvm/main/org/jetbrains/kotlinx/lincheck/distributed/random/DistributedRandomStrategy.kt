/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.distributed.random

import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.CompletedInvocationResult
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.toLincheckFailure
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.Integer.max
import java.lang.reflect.Method


/**
 * Represents random strategy.
 * The execution order is generated randomly, and the system faults are introduced in random places.
 */
internal class DistributedRandomStrategy<Message>(
    testCfg: DistributedCTestConfiguration<Message>,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    verifier: Verifier
) : DistributedStrategy<Message>(
    testCfg,
    testClass,
    scenario,
    validationFunctions,
    stateRepresentationFunction,
    verifier
) {
    private val probability = ProbabilityModel(testCfg)
    private val runner = DistributedRunner(this, testCfg, testClass, validationFunctions)

    init {
        try {
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    /**
     * Tries to crash [iNode].
     * Checks if the node type supports crashes, when if probability generates crash,
     * and if the crash doesn't violate the max unavailable number of nodes restriction
     */
    override fun tryCrash(iNode: Int) {
        if (testCfg.addressResolver.crashTypeForNode(iNode) != CrashMode.NO_CRASH
            && probability.nodeFailed(iNode)
            && failureManager.canCrash(iNode) // can be time-consuming
        ) {
            failureManager.crashNode(iNode)
            throw CrashError()
        }
    }

    override fun onMessageSent(sender: Int, receiver: Int, messageId: Int) {
        tryCrash(sender)
    }

    override fun beforeDatabaseAccess(iNode: Int) {
        if (!DistributedStateHolder.canCrashBeforeAccessingDatabase) return
        tryCrash(iNode)
    }

    /**
     * Chooses the next task to be completed.
     */
    override fun next(taskManager: TaskManager): Task? {
        val tasks = taskManager.tasks
        val timeTasks = taskManager.timeTasks
        // If no tasks and no time tasks left, or results of all operations are received
        // and only timers left, return null
        if (tasks.isEmpty() && (timeTasks.isEmpty() || runner.hasAllResults()
                    && timeTasks.all { it is PeriodicTimer })
        ) return null
        // Adds to the tasks the time tasks which are chosen by Poisson probability.
        // The closer the task time is to the current time, the more likely it is that the task will be selected
        val time = taskManager.time
        var tasksToProcess: List<Task>
        do {
            tasksToProcess =
                timeTasks.filter { time > it.time || probability.poissonProbability(it.time - time) } + tasks
        } while (tasksToProcess.isEmpty())
        val task = tasksToProcess.random(probability.rand)
        taskManager.removeTask(task)
        return task
    }

    override fun reset() {
        probability.reset()
        failureManager.reset()
    }

    override fun run(): LincheckFailure? {
        runner.use { runner ->
            // Run invocations
            for (invocation in 0 until testCfg.invocationsPerIteration) {
                reset()
                when (val ir = runner.run()) {
                    is CompletedInvocationResult -> {
                        val verifyResult = if (verifier is DistributedVerifier) {
                            verifier.verifyResultsAndStates(runner.nodes, scenario, ir.results, runner.events)
                        } else {
                            verifier.verifyResults(scenario, ir.results)
                        }
                        if (!verifyResult) {
                            return IncorrectResultsFailure(
                                scenario,
                                ir.results
                            ).also {
                                runner.storeEventsToFile(it)
                            }
                        }
                    }
                    else -> {
                        return ir.toLincheckFailure(scenario).also {
                            runner.storeEventsToFile(it)
                        }
                    }
                }
            }
            return null
        }
    }

    /**
     * Tries to add partition, and if succeed calls [org.jetbrains.kotlinx.lincheck.distributed.DistributedRunner.onPartition]
     * to add it.
     */
    override fun tryAddPartitionBeforeSend(sender: Int, receiver: Int, messageId: Int): Boolean {
        if (testCfg.addressResolver.partitionTypeForNode(sender) != NetworkPartitionMode.NONE
            && probability.isNetworkPartition(sender)
            && failureManager.canAddPartition(sender, receiver)
        ) {
            val partitionResult = failureManager.partition(sender, receiver)
            runner.onPartition(partitionResult.firstPart, partitionResult.secondPart, partitionResult.partitionId)
            return true
        }
        return false
    }

    override fun getMessageRate(sender: Int, receiver: Int, messageId: Int): Int = probability.duplicationRate()

    override fun choosePartitionComponent(nodes: List<Int>, limit: Int): List<Int> =
        probability.partition(nodes, limit)

    override fun getRecoverTimeout(taskManager: TaskManager): Int {
        val time = taskManager.time
        val maxTimeout =
            max(taskManager.timeTasks.maxOfOrNull { it.time - time } ?: 0, ProbabilityModel.DEFAULT_RECOVER_TIMEOUT)
        return probability.recoverTimeout(maxTimeout)
    }

    override fun recoverPartition(firstPart: List<Int>, secondPart: List<Int>) {
        failureManager.removePartition(firstPart, secondPart)
    }

    override fun shouldRecover(iNode: Int): Boolean {
        return when (testCfg.addressResolver.crashTypeForNode(iNode)) {
            CrashMode.FINISH_ON_CRASH -> false
            CrashMode.RECOVER_ON_CRASH -> true
            CrashMode.FINISH_OR_RECOVER_ON_CRASH -> probability.nodeRecovered()
            else -> throw IllegalArgumentException()
        }
    }
}