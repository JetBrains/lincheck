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
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.createLincheckResult
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.withEmptyClock
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.lang.NullPointerException
import java.lang.reflect.Method
import java.util.*

val debugLogs = mutableListOf<String>()

val debugOutput = true
fun addToFile(filename: String, f: (BufferedWriter) -> Unit) {
    if (!debugOutput) return
    FileOutputStream(filename, true).bufferedWriter().use {
        f(it)
    }
}

class DistributedModelCheckingRunner<Message, Log>(
    strategy: DistributedModelCheckingStrategy<Message, Log>,
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

    val tasks = mutableMapOf<Int, Task>()
    var curTreeNode: InterleavingTreeNode? = null
    val path = mutableListOf<InterleavingTreeNode>()
    var interleaving: Interleaving? = null
    val dispatcher = ModelCheckingDispatcher(this)
    var signal = Signal()
    lateinit var environments: Array<MCEnvironmentImpl<Message, Log>>
    val context = ModelCheckingContext(testCfg, scenario)
    val numberOfNodes = context.addressResolver.totalNumberOfNodes
    var exception: Throwable? = null
    val root: InterleavingTreeNode =
        InterleavingTreeNode(-1, context, OperationTask(-1, VectorClock(IntArray(numberOfNodes)), "start") {})
    var builder = InterleavingTreeBuilder(0)
    var isInterrupted = false
    var numberOfSwitches: Int = 0
    var counter = 0
    val previousEvents = mutableListOf<String>()
    val previousPaths = mutableListOf<List<Pair<Int, Task>>>()

    override fun initialize() {
        super.initialize()
        testNodeExecutions = Array(context.addressResolver.nodesWithScenario) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
        context.runner = this
    }

    fun isFullyExplored() = root.isFullyExplored

    fun addTask(task: Task, id: Int? = null) {
        val taskId = id ?: context.tasksId++
        check(!tasks.containsKey(taskId))
        tasks[taskId] = task
    }

    fun bfsPrint() {
        val queue = LinkedList<Pair<Int, InterleavingTreeNode>>()
        var curId = 0
        queue.add(0 to root)
        while (queue.isNotEmpty()) {
            val e = queue.poll()
            if (e.first != curId) {
                curId = e.first
            }
            println("${e.second.id} ${e.second.task.clock} ${e.second.task.msg} next=${e.second.nextPossibleTasksIds}, filtered=${e.second.notCheckedTasks}, allFiltered=${e.second.allNotChecked}")
            queue.addAll(e.second.children.values.map { curId + 1 to it })
        }
        println()
    }

    fun reset() {
        isInterrupted = false
        debugLogs.clear()
        curTreeNode = root
        path.clear()
        tasks.clear()
        builder = InterleavingTreeBuilder(numberOfSwitches)
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

    private suspend fun runNode(iNode: Int) {
        if (iNode >= context.addressResolver.nodesWithScenario) {
            return
        }
        val testNodeExecution = context.testNodeExecutions[iNode]
        val scenarioSize = scenario.parallelExecution[iNode].size
        if (testNodeExecution.actorId == scenarioSize + 1) return
        if (testNodeExecution.actorId == scenarioSize) {
            context.testInstances[iNode].onScenarioFinish()
            return
        }
        val i = testNodeExecution.actorId
        val actor = scenario.parallelExecution[iNode][i]
        context.events.add(
            iNode to
                    OperationStartEvent(
                        actor,
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
        addTask(
            OperationTask(
                iNode,
                VectorClock(context.incClockAndCopy(iNode)),
                "Run node $iNode ${testNodeExecution.actorId}"
            ) {
                runNode(iNode)
            })
    }

    private suspend fun handleException(f: suspend () -> Unit): Boolean {
        return try {
            f()
            true
        } catch (e: Throwable) {
            if (exception == null) {
                exception = e
            }
            false
        }
    }

    private suspend fun executeTask(next: Int) {
        path.add(curTreeNode!!)
        signal = Signal()
        GlobalScope.launch(dispatcher + TaskContext()) {
            handleException {
                tasks[next]!!.f()
            }
        }
        signal.await()
        curTreeNode!!.finish(tasks)
        tasks.remove(next)
    }

    fun nextTransition(): Int? {
        if (curTreeNode == null) return null
        val curTaskId = curTreeNode!!.id
        val index = interleaving!!.path.indexOf(curTaskId)
        if (index == -1 || index == interleaving!!.path.size - 1) return null
        return interleaving!!.path[index + 1]
    }

    override fun run(): InvocationResult {
        reset()
        tasks[root.id] = root.task
        val coroutine = GlobalScope.launch(dispatcher) {
            for (i in 0 until numberOfNodes) {
                context.incClock(i)
                context.testInstances[i].onStart()
            }
            for (i in 0 until numberOfNodes) {
                addTask(OperationTask(i, VectorClock(context.copyClock(i)), "Run $i") {
                    runNode(i)
                })
            }
            curTreeNode!!.finish(tasks)
            tasks.remove(-1)
            interleaving = root.chooseNextInterleaving(builder)
            path.add(curTreeNode!!)
            debugLogs.add("path=${interleaving!!.path}")
            try {
                for (next in interleaving!!.path) {
                    curTreeNode = curTreeNode!![next]
                    executeTask(next)
                    if (exception != null) return@launch
                }
                //println(if (curTreeNode?.children?.isEmpty() == true) "-------" else "false")
                while (tasks.isNotEmpty()) {
                    val next = curTreeNode!!.next()
                    if (next == null) {
                        var up = curTreeNode
                        try {
                            while (!tasks.any { it.key in up!!.parent!!.nextPossibleTasksIds }) {
                                up = up!!.parent
                            }
                        } catch (e: NullPointerException) {
                            bfsPrint()
                            exception = e
                            return@launch
                        }
                        check(up!!.task !is NodeCrashTask)
                        counter++
                        up!!.parent!!.nextPossibleTasksIds.remove(up.id)
                        up!!.parent!!.children.remove(up.id)

                        while (tasks.isNotEmpty()) {
                            val next = tasks.minOfOrNull { it.key }!!
                            signal = Signal()
                            GlobalScope.launch(dispatcher + TaskContext()) {
                                handleException {
                                    tasks[next]!!.f()
                                }
                            }
                            signal.await()
                            if (exception != null) return@launch
                            tasks.remove(next)
                        }
                        isInterrupted = true
                        break
                    }
                    curTreeNode = curTreeNode!![next]
                    executeTask(next)
                }
            } catch (e: NullPointerException) {
                bfsPrint()
                exception = e
                return@launch
            }
        }
        try {
            runBlocking {
                withTimeout(testCfg.timeoutMs) {
                    coroutine.join()
                }
            }
        } catch (e: TimeoutCancellationException) {
            return DeadlockInvocationResult(emptyMap())
        }
        /*if (!isInterrupted) {
            /*addToFile("${hashCode()}@interleavings.txt") {
                it.appendLine("$numberOfSwitches ${root.minDistance} ${root.fractionUnexplored}" + interleaving?.path.toString())
            }
            addToFile("${hashCode()}@lamport_path.txt") {
                it.appendLine(path.map { "{id=${it.id}, msg=${it.task}}" }.toString())
            }
            addToFile("${hashCode()}@lamport_info.txt") { it.appendLine(context.events.toString()) }
            addToFile("${hashCode()}@lamport_ids.txt") { it.appendLine(path.map { it.id }.toString()) }*/
            val e = context.events.toString()
            if (e in previousEvents) {
                val same = previousEvents.lastIndexOf(e)
                addToFile("${hashCode()}@path.txt") {
                    it.appendLine(same.toString())
                    previousPaths[same].forEach { i -> it.appendLine(i.toString()) }
                    it.appendLine(previousPaths.size.toString())
                    path.map { it.id to it.task }.forEach { i -> it.appendLine(i.toString()) }
                    it.appendLine()
                }
                addToFile("${hashCode()}@logs.txt") {
                    it.appendLine(same.toString())
                    it.appendLine(previousEvents[same])
                    //previousEvents[same].forEach { i -> it.appendLine(i.toString()) }
                    it.appendLine(previousEvents.size.toString())
                    it.appendLine(e)
                    //e.forEach { i -> it.appendLine(i.toString()) }
                    it.appendLine()
                }
            }
            previousEvents.add(e)
            previousPaths.add(path.map { it.id to it.task })
        }*/
        //println(path[0])
        path.reversed().forEach { it.updateStats() }
        if (root.minDistance > numberOfSwitches) {
            numberOfSwitches++
        }
        //println("Current number of switches $numberOfSwitches, min distance ${root.minDistance}")
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

    fun storeEventsToFile(failure: LincheckFailure) {
        if (testCfg.logFilename == null) return
        File(testCfg.logFilename).printWriter().use { out ->
            out.println(failure)
            out.println()
            context.events.toList().forEach { p ->
                out.println("${p.first} # ${p.second}")
            }
        }
    }
}