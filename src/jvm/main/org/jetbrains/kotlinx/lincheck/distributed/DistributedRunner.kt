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

package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.VoidResult
import org.jetbrains.kotlinx.lincheck.createExceptionResult
import org.jetbrains.kotlinx.lincheck.createLincheckResult
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.withEmptyClock
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.io.File
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

fun debugOutput(f: () -> String) {
    return
    val msg = Thread.currentThread().name + ": " + f()
    println(msg)
}

internal open class DistributedRunner<Message, Log>(
    strategy: DistributedStrategy<Message, Log>,
    val testCfg: DistributedCTestConfiguration<Message, Log>,
    testClass: Class<*>,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?
) : Runner(strategy, testClass, validationFunctions, stateRepresentationFunction) {
    private val distrStrategy: DistributedStrategy<Message, Log> = strategy
    private val numberOfNodes = testCfg.addressResolver.totalNumberOfNodes
    private lateinit var eventFactory: EventFactory<Message, Log>
    private lateinit var environments: Array<EnvironmentImpl<Message, Log>>
    private lateinit var nodeInstances: Array<Node<Message, Log>>
    private lateinit var testNodeExecutions: Array<TestNodeExecution>
    private lateinit var taskManager: TaskManager
    var continuation: Continuation<Unit>? = null
    private lateinit var dispatcher: DistributedDispatcher
    private var exception: Throwable? = null

    override fun initialize() {
        super.initialize()
        testNodeExecutions = Array(testCfg.addressResolver.nodesWithScenario) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
    }

    private fun reset() {
        dispatcher = DistributedDispatcher(this)
        exception = null
        eventFactory = EventFactory(testCfg)
        taskManager = if (testCfg.messageOrder == MessageOrder.FIFO) {
            FifoTaskManager()
        } else {
            NoFifoTaskManager()
        }
        environments = Array(numberOfNodes) {
            EnvironmentImpl(
                it,
                numberOfNodes,
                LogList(distrStrategy, it),
                eventFactory,
                distrStrategy,
                taskManager
            )
        }
        nodeInstances = Array(numberOfNodes) {
            testCfg.addressResolver[it].getConstructor(Environment::class.java)
                .newInstance(environments[it])
        }
        testNodeExecutions.forEachIndexed { t, ex ->
            ex.testInstance = nodeInstances[t]
            val actors = scenario.parallelExecution[t].size
            ex.results = arrayOfNulls(actors)
            ex.actorId = 0
        }
        eventFactory.nodeInstances = nodeInstances
    }

    override fun run(): InvocationResult {
        reset()
        dispatcher.use { dispatcher ->
            for (i in 0 until numberOfNodes) {
                taskManager.addTask(OperationTask(i) {
                    nodeInstances[i].onStart()
                    taskManager.addTask(OperationTask(i) {
                        runNode(i)
                    })
                })
            }
            val coroutine = createMainCoroutine()
            try {
                runBlocking {
                    withTimeout(testCfg.timeoutMs) {
                        coroutine.join()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                return DeadlockInvocationResult(emptyMap())
            }

            if (exception != null) {
                return UnexpectedExceptionInvocationResult(exception!!)
            }
            try {
                val logs: Array<List<Log>> = Array(numberOfNodes) {
                    environments[it].log
                }
                nodeInstances.forEach { n -> n.validate(eventFactory.events, logs) }
            } catch (e: Throwable) {
                return ValidationFailureInvocationResult(scenario, "validate", e)
            }
            nodeInstances.forEach {
                executeValidationFunctions(it, validationFunctions) { functionName, exception ->
                    val s = ExecutionScenario(
                        scenario.initExecution,
                        scenario.parallelExecution,
                        emptyList()
                    )
                    return ValidationFailureInvocationResult(s, functionName, exception)
                }
            }
            testNodeExecutions.zip(scenario.parallelExecution).forEach { it.first.setSuspended(it.second) }
            val parallelResultsWithClock = testNodeExecutions.mapIndexed { i, ex ->
                //TODO: add real clock
                ex.results.map { it!!.withEmptyClock(numberOfNodes) }
            }
            val results = ExecutionResult(
                emptyList(), null, parallelResultsWithClock, super.constructStateRepresentation(),
                emptyList(), null
            )
            return CompletedInvocationResult(results)
        }
    }

    private fun createMainCoroutine() = GlobalScope.launch(dispatcher) {
        while (true) {
            val next = distrStrategy.next(taskManager) ?: break
            suspendCoroutine<Unit> { cont ->
                continuation = cont
                GlobalScope.launch(dispatcher) {
                    try {
                        next.f()
                    } catch (e: CrashError) {
                        onNodeCrash(next.iNode)
                    } catch (e: Throwable) {
                        if (exception == null) {
                            exception = e
                        }
                    }
                }
            }
            continuation = null
            if (exception != null) {
                break
            }
        }
        continuation = null
    }

    private fun onNodeCrash(iNode: Int) {
        eventFactory.createNodeCrashEvent(iNode)
        taskManager.removeAllForNode(iNode)
        testNodeExecutions.getOrNull(iNode)?.crash()
        nodeInstances.forEachIndexed { index, node ->
            if (index != iNode) {
                taskManager.addTask(CrashNotificationTask(index) {
                    eventFactory.createCrashNotificationEvent(index, iNode)
                    node.onNodeUnavailable(iNode)
                })
            }
        }
        if (testCfg.supportRecovery == CrashMode.NO_RECOVERIES) {
            testNodeExecutions.getOrNull(iNode)?.crashRemained()
            return
        }
        taskManager.addTask(NodeRecoverTask(iNode) {
            environments[iNode] =
                EnvironmentImpl(iNode, numberOfNodes, environments[iNode].log, eventFactory, distrStrategy, taskManager)

            nodeInstances[iNode] = testCfg.addressResolver[iNode].getConstructor(Environment::class.java)
                .newInstance(environments[iNode])
            testNodeExecutions.getOrNull(iNode)?.testInstance = nodeInstances[iNode]
            distrStrategy.onNodeRecover(iNode)
            eventFactory.createNodeRecoverEvent(iNode)
            nodeInstances[iNode].recover()
            taskManager.addTask(OperationTask(iNode) {
                runNode(iNode)
            })
        })
    }

    fun hasAllResults() = testNodeExecutions.all { it.results.none { r -> r == null } }

    private suspend fun runNode(iNode: Int) {
        if (iNode >= testCfg.addressResolver.nodesWithScenario) {
            return
        }
        val testNodeExecution = testNodeExecutions[iNode]
        val scenarioSize = scenario.parallelExecution[iNode].size
        if (testNodeExecution.actorId == scenarioSize + 1) return
        if (testNodeExecution.actorId == scenarioSize) {
            eventFactory.createScenarioFinishEvent(iNode)
            nodeInstances[iNode].onScenarioFinish()
            return
        }
        val i = testNodeExecution.actorId
        val actor = scenario.parallelExecution[iNode][i]
        eventFactory.createOperationEvent(actor, iNode)
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
                testNodeExecutions[iNode].results[i] = createExceptionResult(e.javaClass)
            } else {
                throw e
            }
        }
        taskManager.addTask(
            OperationTask(iNode) {
                runNode(iNode)
            }
        )
    }

    fun storeEventsToFile(failure: LincheckFailure) {
        val printInstance = testCfg.addressResolver.isMultipleType
        if (testCfg.logFilename == null) return
        File(testCfg.logFilename).printWriter().use { out ->
            out.println(failure)
            out.println()
            eventFactory.events.toList().forEach { p ->
                val header = if (printInstance) {
                    "${p.iNode}, ${testCfg.addressResolver[p.iNode].simpleName}"
                } else {
                    p.iNode
                }
                out.println("[${header}]: $p")
            }
        }
    }
}