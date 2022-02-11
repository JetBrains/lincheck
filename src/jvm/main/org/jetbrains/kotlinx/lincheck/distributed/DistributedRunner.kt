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

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.jetbrains.kotlinx.lincheck.VoidResult
import org.jetbrains.kotlinx.lincheck.createExceptionResult
import org.jetbrains.kotlinx.lincheck.createLincheckResult
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.distributed.event.EventFactory
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.withEmptyClock
import org.jetbrains.kotlinx.lincheck.runner.*
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Executes the distributed algorithms.
 */
internal open class DistributedRunner<S : DistributedStrategy<Message>, Message>(
    strategy: S,
    val testCfg: DistributedCTestConfiguration<Message>,
    testClass: Class<*>,
    validationFunctions: List<Method>
) : Runner<S>(strategy, testClass, validationFunctions, null) {
    companion object {
        const val TASK_LIMIT = 10_000
    }

    /**
     * Total number of nodes.
     */
    private val nodeCount = testCfg.addressResolver.nodeCount

    /**
     * Logs all events which occurred during the execution,
     */
    private lateinit var eventFactory: EventFactory<Message>

    /**
     * Node environments.
     */
    private lateinit var environments: Array<NodeEnvironment<Message>>

    /**
     * Node instances.
     */
    lateinit var nodes: Array<Node<Message>>

    /**
     * Executes operations from the scenario.
     */
    private lateinit var testNodeExecutions: Array<TestNodeExecution>

    /**
     * Stores all tasks which occurred during the execution.
     */
    private lateinit var taskManager: TaskManager

    /**
     * Executor for the node tasks.
     */
    private lateinit var executor: DistributedExecutor

    /**
     * Signals when the execution is over.
     */
    private val lock = ReentrantLock()
    private val completionCondition = lock.newCondition()

    /**
     *
     */
    @Volatile
    private var isTaskLimitExceeded = false

    @Volatile
    private var exception: Throwable? = null

    val events: List<Event>
        get() = eventFactory.events

    override fun initialize() {
        super.initialize()
        DistributedStateHolder.setState(classLoader, DistributedState())
        testNodeExecutions = Array(testCfg.addressResolver.scenarioSize) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
        executor = DistributedExecutor(this)
    }

    /**
     * Resets the runner to the initial state before next invocation.
     */
    private fun reset() {
        // Create new instances.
        eventFactory = EventFactory(testCfg)
        taskManager = TaskManager(testCfg.messageOrder)
        environments = Array(nodeCount) {
            NodeEnvironment(
                it,
                nodeCount,
                eventFactory,
                strategy,
                taskManager
            )
        }
        nodes = Array(nodeCount) {
            testCfg.addressResolver[it].getConstructor(NodeEnvironment::class.java)
                .newInstance(environments[it])
        }
        // Set node instances to test node executions
        testNodeExecutions.forEachIndexed { t, ex ->
            ex.testInstance = nodes[t]
            val actors = scenario.parallelExecution[t].size
            ex.results = arrayOfNulls(actors)
            ex.actorId = 0
        }
        // Set nodes for event factory
        eventFactory.nodeInstances = nodes
        DistributedStateHolder.canCrashBeforeAccessingDatabase = true
    }

    /**
     * Runs the invocation.
     */
    override fun run(): InvocationResult {
        reset()
        addInitialTasks()
        launchNextTask()
        // Wait until the execution is over or time limit is exceeded.
        val finishedOnTime = try {
            lock.withLock { completionCondition.await(testCfg.timeoutMs, TimeUnit.MILLISECONDS) }
        } catch (_: InterruptedException) {
            //TODO (don't know how to handle it correctly)
            false
        }
        DistributedStateHolder.canCrashBeforeAccessingDatabase = false
        // The execution didn't finish within the [org.jetbrains.kotlinx.lincheck.Options.timeoutMs]
        if (!finishedOnTime) {
            executor.shutdownNow()
            return LivelockInvocationResult
        }
        // The exception occurred during the execution.
        if (exception != null) {
            return UnexpectedExceptionInvocationResult(exception!!)
        }
        // The number of tasks is exceeded.
        if (isTaskLimitExceeded) {
            return TaskLimitExceededResult
        }
        // The validation function exception.
        nodes.forEach {
            executeValidationFunctions(it, validationFunctions) { functionName, exception ->
                val s = ExecutionScenario(
                    scenario.initExecution,
                    scenario.parallelExecution,
                    emptyList()
                )
                return ValidationFailureInvocationResult(s, functionName, exception)
            }
        }
        // Return the results.
        testNodeExecutions.zip(scenario.parallelExecution).forEach { it.first.setSuspended(it.second) }
        val parallelResults = testNodeExecutions.mapIndexed { i, ex ->
            // TODO: add real clock. Cannot use it now, because checks in verifier (see org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityContext.hblegal)
            ex.results.map { it!!.withEmptyClock(nodeCount) }
        }
        val results = ExecutionResult(
            emptyList(), null, parallelResults, super.constructStateRepresentation(),
            emptyList(), null
        )
        return CompletedInvocationResult(results)
    }

    /**
     * Add initial tasks to task manager.
     */
    private fun addInitialTasks() {
        repeat(nodeCount) { i ->
            taskManager.addActionTask(i) {
                nodes[i].onStart()
                if (i >= testCfg.addressResolver.scenarioSize) return@addActionTask
                taskManager.addSuspendedTask(i) {
                    runNode(i)
                }
            }
        }
    }

    /**
     * Returns true if the next task was executed successfully, false if the execution should be stopped.
     */
    fun launchNextTask(): Boolean {
        if (exception != null) return false
        val next = strategy.next(taskManager) ?: return false
        if (taskManager.taskCount > TASK_LIMIT) {
            isTaskLimitExceeded = true
            return false
        }
        //TODO remove code duplication. Can be solved by updating Kotlin version.
        if (next is InstantTask) {
            executor.execute {
                try {
                    next.action()
                } catch (e: CrashError) {
                    onNodeCrash((next as NodeTask).iNode)
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

    /**
     * Removes all remained tasks for [iNode], sends crash notifications to other nodes,
     * add recover task if necessary.
     */
    private fun onNodeCrash(iNode: Int) {
        eventFactory.createNodeCrashEvent(iNode)
        taskManager.removeAllForNode(iNode)
        testNodeExecutions.getOrNull(iNode)?.crash()
        sendCrashNotifications(iNode)
        if (!strategy.shouldRecover(iNode)) {
            testNodeExecutions.getOrNull(iNode)?.crashRemained()
            return
        }
        taskManager.addCrashRecoverTask(iNode = iNode, ticks = strategy.getRecoverTimeout(taskManager)) {
            environments[iNode] =
                NodeEnvironment(iNode, nodeCount, eventFactory, strategy, taskManager)
            val newInstance = testCfg.addressResolver[iNode].getConstructor(NodeEnvironment::class.java)
                .newInstance(environments[iNode])
            testNodeExecutions.getOrNull(iNode)?.testInstance = newInstance
            if (newInstance is NodeWithStorage<Message, *>) {
                newInstance.onRecovery((nodes[iNode] as NodeWithStorage<Message, *>).storage)
            }
            nodes[iNode] = newInstance
            strategy.onNodeRecover(iNode)
            eventFactory.createNodeRecoverEvent(iNode)
            nodes[iNode].recover()
            taskManager.addSuspendedTask(iNode) {
                runNode(iNode)
            }
        }
    }

    /**
     * Sends crash notifications between nodes from different parts and creates partition recover task.
     */
    fun onPartition(firstPart: List<Int>, secondPart: List<Int>, partitionId: Int) {
        eventFactory.createNetworkPartitionEvent(firstPart, secondPart, partitionId)
        sendCrashNotifications(firstPart, secondPart)
        taskManager.addPartitionRecoverTask(ticks = strategy.getRecoverTimeout(taskManager)) {
            eventFactory.createNetworkRecoverEvent(partitionId)
            strategy.recoverPartition(firstPart, secondPart)
        }
    }

    /**
     * Returns true if results of all operations are received.
     */
    fun hasAllResults() = testNodeExecutions.all { it.results.none { r -> r == null } }

    /**
     * Runs the operations for node.
     */
    private suspend fun runNode(iNode: Int) {
        if (iNode >= testCfg.addressResolver.scenarioSize) {
            return
        }
        val testNodeExecution = testNodeExecutions[iNode]
        val scenarioSize = scenario.parallelExecution[iNode].size
        if (testNodeExecution.actorId == scenarioSize + 1) return
        if (testNodeExecution.actorId == scenarioSize) {
            eventFactory.createScenarioFinishEvent(iNode)
            nodes[iNode].onScenarioFinish()
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
        taskManager.addSuspendedTask(iNode) {
            runNode(iNode)
        }
    }

    /**
     * Sends notification to [iNode] what [crashedNode] is unavailable.
     */
    private fun crashNotification(iNode: Int, crashedNode: Int) {
        if (!strategy.isCrashed(iNode)) {
            taskManager.addActionTask(iNode) {
                eventFactory.createCrashNotificationEvent(iNode, crashedNode)
                nodes[iNode].onNodeUnavailable(crashedNode)
            }
        }
    }

    /**
     * Sends notification about crashed node [iNode].
     */
    private fun sendCrashNotifications(iNode: Int) {
        if (!testCfg.crashNotifications) return
        for (i in 0 until nodeCount) {
            if (i == iNode) continue
            crashNotification(i, iNode)
        }
    }

    /**
     * Sends crash notifications between partitions.
     */
    private fun sendCrashNotifications(firstPart: List<Int>, secondPart: List<Int>) {
        if (!testCfg.crashNotifications) return
        for (firstNode in firstPart) {
            for (secondNode in secondPart) {
                crashNotification(firstNode, secondNode)
                crashNotification(secondNode, firstNode)
            }
        }
    }

    /**
     * Signals that the execution is over.
     */
    fun signal() = lock.withLock {
        completionCondition.signal()
    }

    override fun close() {
        executor.close()
    }
}