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

package org.jetbrains.kotlinx.lincheck.distributed.modelchecking

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.VoidResult
import org.jetbrains.kotlinx.lincheck.createExceptionResult
import org.jetbrains.kotlinx.lincheck.createLincheckResult
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.stress.DistributedRunner
import org.jetbrains.kotlinx.lincheck.distributed.stress.DistributedStrategy
import org.jetbrains.kotlinx.lincheck.distributed.stress.EnvironmentImpl
import org.jetbrains.kotlinx.lincheck.distributed.stress.withProbability
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.withEmptyClock
import org.jetbrains.kotlinx.lincheck.runner.*
import java.lang.reflect.Method
import java.util.concurrent.Executors
import kotlin.math.sign

class DistributedModelCheckingRunner<Message, Log>(
    strategy: DistributedStrategy<Message, Log>,
    val testCfg: DistributedCTestConfiguration<Message, Log>,
    testClass: Class<*>,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?
) : Runner(
    strategy, testClass,
    validationFunctions,
    stateRepresentationFunction
) {
    private lateinit var testNodeExecutions: Array<TestNodeExecution>

    val tasks = mutableMapOf<Int, suspend () -> Unit>()
    var curTreeNode: InterleavingTreeNode? = null
    val interleaving = mutableListOf<InterleavingTreeNode>()
    lateinit var vectorClock: Array<VectorClock>
    val dispatcher = ModelCheckingDispatcher(this)
    var signal = Signal()
    lateinit var environments : Array<MCEnvironmentImpl<Message, Log>>
    val context = ModelCheckingContext(testCfg, scenario)
    val numberOfNodes = context.addressResolver.totalNumberOfNodes
    var exception : Throwable? = null
    val root: InterleavingTreeNode = InterleavingTreeNode(context, -1, VectorClock(IntArray(numberOfNodes)), -1)

    override fun initialize() {
        super.initialize()
        testNodeExecutions = Array(context.addressResolver.nodesWithScenario) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
    }

    fun addTask(iNode: Int, parentClock: VectorClock, f: suspend () -> Unit) {
        val taskId = curTreeNode!!.addChoice(parentClock, iNode)
        check(!tasks.containsKey(taskId))
        tasks[taskId] = f
    }

    fun reset() {
        curTreeNode = root
        interleaving.clear()
        context.reset()
        environments = Array(numberOfNodes) {
            MCEnvironmentImpl(it, numberOfNodes, context = context)
        }
        context.testInstances = Array(numberOfNodes) {
            context.addressResolver[it].getConstructor(Environment::class.java)
                .newInstance(environments[it]) as Node<Message>
        }
        context.testNodeExecutions = testNodeExecutions
        context.testNodeExecutions.forEachIndexed { t, ex ->
            ex.testInstance = context.testInstances[t]
            val actors = scenario.parallelExecution[t].size
            ex.results = arrayOfNulls(actors)
            ex.actorId = 0
        }
    }

    private fun runNode(iNode: Int) {
        if (iNode >= context.addressResolver.nodesWithScenario) {
            return
        }
        val testNodeExecution = context.testNodeExecutions[iNode]
        val scenarioSize = scenario.parallelExecution[iNode].size
        if (testNodeExecution.actorId == scenarioSize + 1) return
        if (testNodeExecution.actorId == scenarioSize) {
            addTask(iNode, VectorClock(context.copyClock(iNode))) {
                context.testInstances[iNode].onScenarioFinish()
            }
            return
        }
        addTask(iNode, VectorClock(context.copyClock(iNode))) {
            val i = testNodeExecution.actorId
            val actor = scenario.parallelExecution[iNode][i]
            context.events.add(
                iNode to
                        OperationStartEvent(
                            i,
                            context.incClockAndCopy(iNode),
                            context.getStateRepresentation(iNode)
                        )
            )
            try {
                testNodeExecution.actorId++
                val res = testNodeExecution.runOperation(i)
                testNodeExecution.results[i] = if (actor.method.returnType == Void.TYPE) {
                    VoidResult
                } else {
                    createLincheckResult(res)
                }
            } catch (e: Throwable) {
                if (e.javaClass in actor.handledExceptions) {
                    context.testNodeExecutions[iNode].results[i] = createExceptionResult(e.javaClass)
                } else {
                    onFailure(iNode, e)
                    testNodeExecution.actorId = scenarioSize
                }
            }
            addTask(iNode, VectorClock(context.copyClock(iNode))) {
                runNode(iNode)
            }
        }
    }

    override fun run(): InvocationResult {
        reset()
        val coroutine = GlobalScope.launch(dispatcher) {
            val starts = (0 until numberOfNodes).shuffled(context.generatingRandom)
            for (i in starts) {
                context.testInstances[i].onStart()
            }
            for (i in 0 until numberOfNodes) {
                runNode(i)
            }
            while (curTreeNode != null) {
                interleaving.add(curTreeNode!!)
                val i = curTreeNode!!.iNode
                signal = Signal()
                launch(TaskContext()) {
                    tasks[i]!!()
                }
                signal.await()
                curTreeNode!!.finish()
                curTreeNode = curTreeNode!!.next()
                if (exception != null) return@launch
            }
        }
        runBlocking {
            coroutine.join()
        }
        for (node in interleaving.reversed()) {
            node.updateExplorationStatistics()
        }
        environments.forEach { it.isFinished = true }
        if (exception != null) {
            return UnexpectedExceptionInvocationResult(exception!!)
        }
        repeat(numberOfNodes) {
            context.logs[it] = environments[it].log
        }

        context.testInstances.forEach {
            executeValidationFunctions(it, validationFunctions) { functionName, exception ->
                val s = ExecutionScenario(
                    scenario.initExecution,
                    scenario.parallelExecution,
                    emptyList()
                )
                return ValidationFailureInvocationResult(s, functionName, exception)
            }
        }

        context.testNodeExecutions.zip(scenario.parallelExecution).forEach { it.first.setSuspended(it.second) }
        val parallelResultsWithClock = context.testNodeExecutions.mapIndexed { i, ex ->
            ex.results.map { it!!.withEmptyClock(numberOfNodes) }
        }
        val results = ExecutionResult(
            emptyList(), null, parallelResultsWithClock, super.constructStateRepresentation(),
            emptyList(), null
        )
        return CompletedInvocationResult(results)
    }

    override fun onFailure(iThread: Int, e: Throwable) {
        if (exception == null) {
            exception = e
        }
    }
}