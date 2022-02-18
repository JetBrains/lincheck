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
import org.jetbrains.kotlinx.lincheck.distributed.EventLogMode.*
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
    verifier: Verifier
) : DistributedStrategy<Message>(
    testCfg,
    testClass,
    scenario,
    validationFunctions,
    verifier
) {
    private val probability = ProbabilisticModel(testCfg)
    private val initialLogMode = if (verifier is DistributedVerifier) WITHOUT_STATE else OFF
    private val runner = DistributedRunner(this, testCfg, testClass, validationFunctions, FULL)
    private var interleaving: List<Int>? = null

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
            && probability.nodeCrashed(iNode)
            && failureManager.canCrash(iNode) // can be time-consuming
        ) {
            failureManager.crashNode(iNode)
            throw CrashError()
        }
    }

    override fun onMessageSent(sender: Int, receiver: Int) {
        tryCrash(sender)
    }

    override fun beforeStorageAccess(iNode: Int) {
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
                timeTasks.filter { it.time < time || probability.geometricProbability(it.time - time) } + tasks
        } while (tasksToProcess.isEmpty())
        val task = probability.chooseTask(tasksToProcess)
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
                                scenario = scenario,
                                results = ir.results,
                                crashes = failureManager.crashes,
                                partitions = failureManager.partitionCount,
                                logFilename = testCfg.logFilename
                            ).also {
                                testCfg.getFormatter().storeEventsToFile(
                                    failure = it,
                                    filename = testCfg.logFilename,
                                    events = runner.events
                                )
                            }
                        }
                    }
                    else -> {
                        return ir.toLincheckFailure(
                            scenario = scenario,
                            crashes = failureManager.crashes,
                            partitions = failureManager.partitionCount,
                            logFilename = testCfg.logFilename
                        ).also {
                            testCfg.getFormatter().storeEventsToFile(
                                failure = it,
                                filename = testCfg.logFilename,
                                events = runner.events
                            )
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
    override fun tryAddPartition(sender: Int, receiver: Int): Boolean {
        if (testCfg.addressResolver.partitionTypeForNode(sender) != NetworkPartitionMode.NONE
            && probability.isPartition(sender)
            && failureManager.canAddPartition(sender, receiver)
        ) {
            val partitionResult = failureManager.partition(sender, receiver)
            runner.onPartition(partitionResult.firstPart, partitionResult.secondPart, partitionResult.partitionId)
            return true
        }
        return false
    }

    override fun getMessageRate(sender: Int, receiver: Int): Int = probability.messageRate()

    override fun choosePartitionComponent(nodes: List<Int>, limit: Int): List<Int> =
        probability.choosePartition(nodes, limit)

    override fun getRecoverTimeout(taskManager: TaskManager): Int {
        val time = taskManager.time
        val maxTimeout =
            max(taskManager.timeTasks.maxOfOrNull { it.time - time } ?: 0, ProbabilisticModel.DEFAULT_RECOVER_TIMEOUT)
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

internal class DistributedInterleavingStrategy<Message>(
    testCfg: DistributedCTestConfiguration<Message>,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    verifier: Verifier,
    val interleaving: List<Int>,
    val decisionModel: DecisionModel
) : DistributedStrategy<Message>(
    testCfg,
    testClass,
    scenario,
    validationFunctions,
    verifier
) {
    override fun reset() {
        TODO("Not yet implemented")
    }

    override fun next(taskManager: TaskManager): Task? {
        TODO("Not yet implemented")
    }

    override fun onMessageSent(sender: Int, receiver: Int) {
        TODO("Not yet implemented")
    }

    override fun beforeStorageAccess(iNode: Int) {
        TODO("Not yet implemented")
    }

    override fun choosePartitionComponent(nodes: List<Int>, limit: Int): List<Int> {
        TODO("Not yet implemented")
    }

    override fun getRecoverTimeout(taskManager: TaskManager): Int {
        TODO("Not yet implemented")
    }

    override fun recoverPartition(firstPart: List<Int>, secondPart: List<Int>) {
        TODO("Not yet implemented")
    }

    override fun shouldRecover(iNode: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun tryCrash(iNode: Int) {
        TODO("Not yet implemented")
    }

    override fun getMessageRate(sender: Int, receiver: Int): Int {
        TODO("Not yet implemented")
    }

    override fun tryAddPartition(sender: Int, receiver: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun run(): LincheckFailure? {
        TODO("Not yet implemented")
    }
}