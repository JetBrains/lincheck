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
import org.jetbrains.kotlinx.lincheck.distributed.random.canCrashBeforeAccessingDatabase
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.withEmptyClock
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Executes the distributed algorithms.
 */
internal open class DistributedRunner<Message>(
    strategy: DistributedStrategy<Message>,
    val testCfg: DistributedCTestConfiguration<Message>,
    testClass: Class<*>,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?
) : Runner(strategy, testClass, validationFunctions, stateRepresentationFunction) {
    companion object {
        const val TASK_LIMIT = 10_000
    }

    //Note: cast strategy to DistributedStrategy
    private val distrStrategy: DistributedStrategy<Message> = strategy
    private val nodeCount = testCfg.addressResolver.nodeCount
    private lateinit var eventFactory: EventFactory<Message>

    private lateinit var environments: Array<Environment<Message>>
    lateinit var nodeInstances: Array<Node<Message>>
    private lateinit var testNodeExecutions: Array<TestNodeExecution>
    private lateinit var taskManager: TaskManager
    private var taskCount = 0
    private lateinit var executor: DistributedExecutor
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private var isSignal = false

    @Volatile
    private var isTaskLimitExceeded = false

    @Volatile
    private var exception: Throwable? = null

    val events: List<Event>
        get() = eventFactory.events

    override fun initialize() {
        super.initialize()
        testNodeExecutions = Array(testCfg.addressResolver.scenarioSize) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
        executor = DistributedExecutor(this)
    }

    /**
     * Resets the runner to the initial state before next invocation.
     */
    private fun reset() {
        taskCount = 0
        isSignal = false
        exception = null
        eventFactory = EventFactory(testCfg)
        taskManager = TaskManager(testCfg.messageOrder)
        environments = Array(nodeCount) {
            Environment(
                it,
                nodeCount,
                eventFactory,
                distrStrategy,
                taskManager
            )
        }
        nodeInstances = Array(nodeCount) {
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
        canCrashBeforeAccessingDatabase = true
    }

    /**
     * Runs the invocation.
     */
    override fun run(): InvocationResult {
        reset()
        addInitialTasks()
        launchNextTask()
        val finishedOnTime = try {
            lock.withLock { condition.await(testCfg.timeoutMs, TimeUnit.MILLISECONDS) }
        } catch (_: InterruptedException) {
            //TODO (don't know how to handle it correctly)
            false
        }
        canCrashBeforeAccessingDatabase = false
        // The execution didn't finish within the [org.jetbrains.kotlinx.lincheck.Options.timeoutMs]
        if (!finishedOnTime && !lock.withLock { isSignal }) {
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
        try {
            nodeInstances.forEach { n -> n.validate(eventFactory.events) }
            val storages = nodeInstances.mapIndexed { index, node -> index to node }
                .filter { it.second is NodeWithStorage<Message, *> }
                .associate { it.first to (it.second as NodeWithStorage<Message, *>).storage }
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
            //TODO: add real clock. Cannot use it now, because checks in verifier (see org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityContext.hblegal)
            ex.results.map { it!!.withEmptyClock(nodeCount) }
        }
        val results = ExecutionResult(
            emptyList(), null, parallelResultsWithClock, super.constructStateRepresentation(),
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
                nodeInstances[i].onStart()
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
        val next = distrStrategy.next(taskManager) ?: return false
        taskCount++
        if (taskCount > TASK_LIMIT) {
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
        if (!distrStrategy.shouldRecover(iNode)) {
            testNodeExecutions.getOrNull(iNode)?.crashRemained()
            return
        }
        taskManager.addCrashRecoverTask(iNode = iNode, ticks = distrStrategy.getRecoverTimeout(taskManager)) {
            environments[iNode] =
                Environment(iNode, nodeCount, eventFactory, distrStrategy, taskManager)
            val newInstance = testCfg.addressResolver[iNode].getConstructor(Environment::class.java)
                .newInstance(environments[iNode])
            testNodeExecutions.getOrNull(iNode)?.testInstance = newInstance
            if (newInstance is NodeWithStorage<Message, *>) {
                newInstance.onRecovery((nodeInstances[iNode] as NodeWithStorage<Message, *>).storage)
            }
            nodeInstances[iNode] = newInstance
            distrStrategy.onNodeRecover(iNode)
            eventFactory.createNodeRecoverEvent(iNode)
            nodeInstances[iNode].recover()
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
        taskManager.addPartitionRecoverTask(ticks = distrStrategy.getRecoverTimeout(taskManager)) {
            eventFactory.createNetworkRecoverEvent(partitionId)
            distrStrategy.recoverPartition(firstPart, secondPart)
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
        taskManager.addSuspendedTask(iNode) {
            runNode(iNode)
        }
    }

    /**
     * Sends notification to [iNode] what [crashedNode] is unavailable.
     */
    private fun crashNotification(iNode: Int, crashedNode: Int) {
        if (!distrStrategy.isCrashed(iNode)) {
            taskManager.addActionTask(iNode) {
                eventFactory.createCrashNotificationEvent(iNode, crashedNode)
                nodeInstances[iNode].onNodeUnavailable(crashedNode)
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
    fun signal() {
        lock.withLock {
            isSignal = true
            condition.signal()
        }
    }

    /**
     * Stores the events to file.
     */
    fun storeEventsToFile(failure: LincheckFailure) {
        if (testCfg.logFilename == null) return
        File(testCfg.logFilename).printWriter().use { out ->
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