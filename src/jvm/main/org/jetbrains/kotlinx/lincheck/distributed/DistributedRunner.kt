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

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.VoidResult
import org.jetbrains.kotlinx.lincheck.createExceptionResult
import org.jetbrains.kotlinx.lincheck.createLincheckResult
import org.jetbrains.kotlinx.lincheck.distributed.event.EventFactory
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.withEmptyClock
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit


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
    private val databases = mutableListOf<Log>().apply {
        repeat(numberOfNodes) {
            this.add(testCfg.databaseFactory())
        }
    }
    private lateinit var environments: Array<EnvironmentImpl<Message, Log>>
    private lateinit var nodeInstances: Array<Node<Message, Log>>
    private lateinit var testNodeExecutions: Array<TestNodeExecution>
    private lateinit var taskManager: TaskManager
    private val isSignal = atomic(false)

    @Volatile
    private var exception: Throwable? = null

    private lateinit var executor: DistributedExecutor
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    override fun initialize() {
        super.initialize()
        testNodeExecutions = Array(testCfg.addressResolver.nodesWithScenario) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
        executor = DistributedExecutor(this)
    }

    private fun reset() {
        isSignal.lazySet(false)
        exception = null
        eventFactory = EventFactory(testCfg)
        taskManager = TaskManager(testCfg.messageOrder)
        databases.indices.forEach { databases[it] = testCfg.databaseFactory() }
        environments = Array(numberOfNodes) {
            EnvironmentImpl(
                it,
                numberOfNodes,
                databases[it],
                eventFactory,
                distrStrategy,
                taskManager
            )
        }
        nodeInstances = Array(numberOfNodes) {
            //println("${testCfg.addressResolver[it].simpleName} ${environments[it]}")
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
        for (i in 0 until numberOfNodes) {
            taskManager.addActionTask(i, "On start(iNode=$i)") {
                nodeInstances[i].onStart()
                if (i >= testCfg.addressResolver.nodesWithScenario) return@addActionTask
                taskManager.addSuspendedTask(i, "Run operation(iNode=$i)") {
                    runNode(i)
                }
            }
        }
        launchNextTask()
        val finishedOnTime = try {
            lock.withLock { condition.await(testCfg.timeoutMs, TimeUnit.MILLISECONDS) }
        } catch (_: InterruptedException) {
            //TODO (don't know how to handle it correctly)
            false
        }
        if (!finishedOnTime && !isSignal.value) {
            val stackTrace = executor.shutdownNow() ?: emptyArray()
            return LivelockInvocationResult(taskManager.allTasks, stackTrace)
        }
        if (exception != null) {
            return UnexpectedExceptionInvocationResult(exception!!)
        }
        try {
            nodeInstances.forEach { n -> n.validate(eventFactory.events, databases) }
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

    fun launchNextTask(): Boolean {
        if (exception != null) return false
        val next = distrStrategy.next(taskManager) ?: return false
        //TODO remove code duplication
        //if (next is Timeout) println("Launching timeout")
        if (next is InstantTask) {
            executor.execute {
                try {
                    next.action()
                } catch (e: CrashError) {
                    onNodeCrash(next.iNode)
                } catch (e: Throwable) {
                    if (exception == null) {
                        exception = e
                    }
                }
            }
        }
        if (next is SuspendedTask) {
            GlobalScope.launch(executor.asCoroutineDispatcher()) {
                try {
                    next.action()
                } catch (e: CrashError) {
                    onNodeCrash(next.iNode)
                } catch (e: Throwable) {
                    if (exception == null) {
                        exception = e
                    }
                }
            }
        }
        return true
    }

    fun signal() {
        isSignal.lazySet(true)
        lock.withLock {
            condition.signal()
        }
    }

    private fun onNodeCrash(iNode: Int) {
        println("[$iNode]: Crashed")
        eventFactory.createNodeCrashEvent(iNode)
        taskManager.removeAllForNode(iNode)
        testNodeExecutions.getOrNull(iNode)?.crash()
        nodeInstances.forEachIndexed { index, node ->
            if (index != iNode) {
                taskManager.addActionTask(index, "Crash notification(iNode=$index, crashNode=$iNode)") {
                    eventFactory.createCrashNotificationEvent(index, iNode)
                    node.onNodeUnavailable(iNode)
                }
            }
        }
        if (testCfg.addressResolver.crashTypeForNode(iNode) == CrashMode.NO_RECOVER) {
            testNodeExecutions.getOrNull(iNode)?.crashRemained()
            return
        }
        taskManager.addActionTask(iNode, "Recover(iNode=$iNode)") {
            environments[iNode] =
                EnvironmentImpl(iNode, numberOfNodes, databases[iNode], eventFactory, distrStrategy, taskManager)

            nodeInstances[iNode] = testCfg.addressResolver[iNode].getConstructor(Environment::class.java)
                .newInstance(environments[iNode])
            testNodeExecutions.getOrNull(iNode)?.testInstance = nodeInstances[iNode]
            distrStrategy.onNodeRecover(iNode)
            eventFactory.createNodeRecoverEvent(iNode)
            nodeInstances[iNode].recover()
            taskManager.addSuspendedTask(iNode, "Run operation(iNode=$iNode)") {
                runNode(iNode)
            }
        }
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
        taskManager.addSuspendedTask(iNode, "Run operation(iNode=$iNode)") {
            runNode(iNode)
        }
    }

    fun storeEventsToFile(failure: LincheckFailure) {
        if (testCfg.logFilename == null) return
        File(testCfg.logFilename).printWriter().use { out ->
            println(Thread.currentThread().name)
            out.println(failure)
            out.println()
            val formatter = testCfg.getFormatter()
            val events = eventFactory.events.toList()
            val list = formatter.format(events)
            list.take(2000).forEach {
                out.println(it)
            }
            //testCfg.getFormatter().format(eventFactory.events).forEach { out.println(it) }
        }
    }

    override fun close() {
        executor.close()
    }
}