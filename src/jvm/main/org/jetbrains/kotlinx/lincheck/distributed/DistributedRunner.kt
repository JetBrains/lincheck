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

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.jetbrains.kotlinx.lincheck.VoidResult
import org.jetbrains.kotlinx.lincheck.createExceptionResult
import org.jetbrains.kotlinx.lincheck.createLincheckResult
import org.jetbrains.kotlinx.lincheck.distributed.EventLogMode.FULL
import org.jetbrains.kotlinx.lincheck.distributed.EventLogMode.OFF
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.distributed.event.EventFactory
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.withEmptyClock
import org.jetbrains.kotlinx.lincheck.runner.*
import java.lang.reflect.Method
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal enum class EventLogMode {
    FULL,
    WITHOUT_STATE,
    OFF
}

/**
 * Executes the distributed algorithms.
 */
internal open class DistributedRunner<S : DistributedStrategy<Message>, Message>(
    strategy: S,
    val testCfg: DistributedCTestConfiguration<Message>,
    testClass: Class<*>,
    validationFunctions: List<Method>,
    val logMode: EventLogMode
) : Runner<S>(strategy, testClass, validationFunctions, null) {
    /**
     * Total number of nodes.
     */
    private val nodeCount = testCfg.addressResolver.nodeCount

    /**
     * Logs all events which occurred during the execution,
     */
    private var eventFactory: EventFactory<Message>? = null

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
    private val completionCondition = Semaphore(1)

    @Volatile
    private var exception: Throwable? = null

    val events: List<Event>
        get() = eventFactory?.events ?: emptyList()

    override fun initialize() {
        super.initialize()
        val previousProperty = System.getProperties()["kotlinx.coroutines.debug"] as String?
        System.setProperty("kotlinx.coroutines.debug", "off")
        DistributedStateHolder.setState(classLoader, DistributedState(previousProperty))
        testNodeExecutions = Array(testCfg.addressResolver.scenarioSize) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
        executor = DistributedExecutor(this)
    }

    /**
     * Resets the runner to the initial state before next invocation.
     */
    private fun reset() {
        completionCondition.tryAcquire()
        // Create new instances.
        if (logMode != OFF) eventFactory = EventFactory(testCfg, storeStates = logMode == FULL)
        taskManager = TaskManager(testCfg.messageOrder, nodeCount)
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
        environments.forEach { it.nodeInstances = nodes }
        // Set node instances to test node executions
        testNodeExecutions.forEachIndexed { t, ex ->
            ex.testInstance = nodes[t]
            val actors = scenario.parallelExecution[t].size
            ex.results = arrayOfNulls(actors)
            ex.actorId = 0
        }
        // Set nodes for event factory
        eventFactory?.nodeInstances = nodes
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
        val finishedOnTime = completionCondition.tryAcquire(testCfg.timeoutMs, TimeUnit.MILLISECONDS)
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
        if (taskManager.taskLimitExceeded) {
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
     * Returns true if the next task was executed successfully, false if the execution should be stopped.
     */
    fun launchNextTask(): Boolean {
        if (exception != null) return false
        if (taskManager.taskLimitExceeded) {
            return false
        }
        val next = strategy.next(taskManager) ?: return false
        executeTask(next)
        return true
    }

    /**
     * Executes the [task].
     */
    private fun executeTask(task: Task) {
        when (task) {
            // If the task is suspending, the new coroutine is launched using executor as dispatcher.
            is SuspendedTask -> GlobalScope.launch(executor.asCoroutineDispatcher()) {
                try {
                    task.action()
                } catch (e: CrashError) {
                    onNodeCrash(task.iNode)
                } catch (e: Throwable) {
                    setException(e)
                }
            }
            // If the task is not suspending, it executed directly in the executor,
            // because launching new coroutine each time is time-consuming.
            is InstantTask -> executor.execute {
                try {
                    task.action()
                } catch (e: CrashError) {
                    onNodeCrash((task as NodeTask).iNode)
                } catch (e: Throwable) {
                    setException(e)
                }
            }
        }
    }

    /**
     * Stores the first exception which occurred during the execution.
     */
    private fun setException(e: Throwable) {
        if (exception == null) {
            exception = e
        }
    }

    /**
     * Returns true if results of all operations are received.
     */
    fun hasAllResults() = testNodeExecutions.all { it.results.none { r -> r == null } }

    override fun close() {
        executor.close()
        DistributedStateHolder.resetProperty()
    }

    /**
     * Signals that the execution is over.
     */
    fun signal() = completionCondition.release()

    /**
     * Add initial tasks to task manager.
     */
    private fun addInitialTasks() {
        repeat(nodeCount) { i ->
            // For each node 'onStart' is called.
            taskManager.addActionTask(i) {
                nodes[i].onStart()
                // Return if the node has no operations.
                if (i >= testCfg.addressResolver.scenarioSize) return@addActionTask
                // Task to execute node's operations.
                taskManager.addSuspendedTask(i) {
                    runOperations(i)
                }
            }
        }
    }

    /**
     * Runs the operations for node.
     */
    private suspend fun runOperations(iNode: Int) {
        val testNodeExecution = testNodeExecutions[iNode]
        val scenarioSize = scenario.parallelExecution[iNode].size
        when (val actorId = testNodeExecution.actorId) {
            // All operations and 'onScenarioFinished' are completed
            scenarioSize + 1 -> return
            scenarioSize -> {
                testNodeExecution.actorId++
                eventFactory?.createScenarioFinishEvent(iNode)
                // Call 'onScenarioFinish'.
                nodes[iNode].onScenarioFinish()
            }
            else -> {
                val actor = scenario.parallelExecution[iNode][actorId]
                eventFactory?.createOperationEvent(actor, iNode)
                try {
                    testNodeExecution.actorId++
                    val res = testNodeExecution.runOperation(actorId)
                    // Store operation result.
                    testNodeExecution.results[actorId] = if (actor.method.returnType == Void.TYPE) {
                        VoidResult
                    } else {
                        createLincheckResult(res)
                    }
                } catch (e: Throwable) {
                    // Check if exception is expected.
                    if (e.javaClass in actor.handledExceptions) {
                        testNodeExecutions[iNode].results[actorId] = createExceptionResult(e.javaClass)
                    } else {
                        throw e
                    }
                }
                // Launch next operation.
                taskManager.addSuspendedTask(iNode) {
                    runOperations(iNode)
                }
            }
        }
    }

    /**
     * Removes all remained tasks for [iNode], sends crash notifications to other nodes,
     * add recover task if necessary.
     */
    private fun onNodeCrash(iNode: Int) {
        eventFactory?.createNodeCrashEvent(iNode)
        // Remove all pending tasks for iNode.
        taskManager.removeAllForNode(iNode)
        // Set current operation result to CRASHED
        testNodeExecutions.getOrNull(iNode)?.crash()
        // Send crash notifications to other nodes.
        sendCrashNotifications(iNode)
        if (!strategy.shouldRecover(iNode)) {
            // Set all remaining operations result to 'NoResult'
            testNodeExecutions.getOrNull(iNode)?.crashRemained()
            return
        }
        // Add task for node recovering.
        taskManager.addCrashRecoverTask(iNode = iNode, ticks = strategy.getRecoverTimeout(taskManager)) {
            recover(iNode)
        }
    }

    /**
     * [iNode] recovery.
     */
    private fun recover(iNode: Int) {
        // Create new instances of NodeEnvironment and Node
        environments[iNode] =
            NodeEnvironment(iNode, nodeCount, eventFactory, strategy, taskManager).also { it.nodeInstances = nodes }
        val newInstance = testCfg.addressResolver[iNode].getConstructor(NodeEnvironment::class.java)
            .newInstance(environments[iNode])
        // Change the instance for testNodeExecution if necessary
        testNodeExecutions.getOrNull(iNode)?.testInstance = newInstance
        // If the node has a storage, set it.
        if (newInstance is NodeWithStorage<Message, *>) {
            newInstance.onRecovery((nodes[iNode] as NodeWithStorage<Message, *>).storage)
        }
        nodes[iNode] = newInstance
        // Remove crashed mark from iNode.
        strategy.onNodeRecover(iNode)
        eventFactory?.createNodeRecoverEvent(iNode)
        // Call recover method.
        nodes[iNode].recover()
        // If node has the scenario, continue to execute the remaining operations.
        if (iNode < testCfg.addressResolver.scenarioSize) taskManager.addSuspendedTask(iNode) {
            runOperations(iNode)
        }
    }

    /**
     * Sends crash notifications between nodes from different parts and creates partition recover task.
     */
    fun onPartition(firstPart: List<Int>, secondPart: List<Int>, partitionId: Int) {
        eventFactory?.createNetworkPartitionEvent(firstPart, secondPart, partitionId)
        // Sends notifications between parts that nodes are unavailable for each other.
        sendCrashNotifications(firstPart, secondPart)
        // Add partition recover task.
        taskManager.addPartitionRecoverTask(ticks = strategy.getRecoverTimeout(taskManager)) {
            eventFactory?.createNetworkRecoverEvent(partitionId)
            // Remove partition.
            strategy.recoverPartition(firstPart, secondPart)
        }
    }

    /**
     * Sends notification to [iNode] what [crashedNode] is unavailable.
     */
    private fun crashNotification(iNode: Int, crashedNode: Int) {
        // Do not send notification to crashed node.
        if (strategy.isCrashed(iNode)) return
        taskManager.addActionTask(iNode) {
            eventFactory?.createCrashNotificationEvent(iNode, crashedNode)
            nodes[iNode].onNodeUnavailable(crashedNode)
        }
    }

    /**
     * Sends notification about crashed node [iNode].
     */
    private fun sendCrashNotifications(iNode: Int) {
        if (!testCfg.crashNotifications) return
        repeat(nodeCount) { i ->
            if (i != iNode) crashNotification(i, iNode)
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
}