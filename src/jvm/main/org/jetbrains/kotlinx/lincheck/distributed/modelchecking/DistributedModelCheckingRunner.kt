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

//val debugLogs = mutableListOf<String>()
val ANSI_RESET = "\u001B[0m"
val ANSI_RED = "\u001B[31m"

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

    val dispatcher = ModelCheckingDispatcher(this)
    var signal = Signal()
    lateinit var environments: Array<MCEnvironmentImpl<Message, Log>>
    val context = ModelCheckingContext(testCfg, scenario)
    val numberOfNodes = context.addressResolver.totalNumberOfNodes
    var exception: Throwable? = null
    val root: InterleavingTreeNode =
        InterleavingTreeNode(context)
    var builder = InterleavingTreeBuilder(0, context.nodeCrashInfo)
    var isInterrupted = false
    var numberOfSwitches: Int = 0
    var counter = 0


    //val pathEnds = mutableSetOf<Int>()
    //val previousPaths = mutableListOf<String>()
    //val previousPaths = mutableListOf<List<Pair<Int, Task>>>()

    override fun initialize() {
        super.initialize()
        testNodeExecutions = Array(context.addressResolver.nodesWithScenario) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
    }

    fun isFullyExplored() = root.isFullyExplored

    fun bfsPrint() {
        val queue = LinkedList<Pair<Int, InterleavingTreeNode>>()
        var curId = 0
        queue.add(0 to root)
        while (queue.isNotEmpty()) {
            val e = queue.poll()
            if (e.first != curId) {
                curId = e.first
            }
            //println("${e.second.id} ${e.second.task.clock} ${e.second.task.msg} next=${e.second.nextPossibleTasksIds}, filtered=${e.second.notCheckedTasks}, allFiltered=${e.second.allNotChecked}")
            queue.addAll(e.second.children.values.map { curId + 1 to it })
        }
        println()
    }

    fun reset() {
        isInterrupted = false
        context.currentTreeNode = root
        context.reset()
        context.dispatcher = dispatcher
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
        context.path.add(root)
    }

    private suspend fun runNode(iNode: Int) {
        if (iNode >= context.addressResolver.nodesWithScenario) {
            return
        }
        val testNodeExecution = context.testNodeExecutions[iNode]
        val scenarioSize = scenario.parallelExecution[iNode].size
        if (testNodeExecution.actorId == scenarioSize + 1) return
        if (testNodeExecution.actorId == scenarioSize) {
            context.events.add(
                iNode to
                        ScenarioFinishEvent(
                            context.incClockAndCopy(iNode),
                            context.getStateRepresentation(iNode)
                        )
            )
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
        context.taskManager.addTask(
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

    override fun run(): InvocationResult {
        reset()
        val coroutine = GlobalScope.launch(dispatcher) {
            context.taskManager.addTask(OperationTask(-1, VectorClock(IntArray(numberOfNodes)), "start") {
                for (i in 0 until numberOfNodes) {
                    context.incClock(i)
                    context.testInstances[i].onStart()
                }
                for (i in 0 until numberOfNodes) {
                    context.taskManager.addTask(OperationTask(i, VectorClock(context.copyClock(i)), "Run $i") {
                        runNode(i)
                    })
                }
            })
            do {
                val res = context.taskManager.getNextTaskAndExecute {
                    signal = Signal()
                    GlobalScope.launch(dispatcher + TaskContext()) {
                        handleException {
                            it.f()
                        }
                    }
                    signal.await()
                }
                if (exception != null) return@launch
            } while (res)
            context.currentTreeNode!!.isFinished = true
        }
        try {
            runBlocking {
                /*withTimeout(testCfg.timeoutMs) {

                }*/
                coroutine.join()
            }

        } catch (e: TimeoutCancellationException) {
            return DeadlockInvocationResult(emptyMap())
        }

        context.path.reversed().forEach { it.updateStats() }
        //println("Root min distance ${root.minDistance}, number of switches ${numberOfSwitches}")
        if (root.minDistance > numberOfSwitches) {
            numberOfSwitches++
            println("Number of switches $numberOfSwitches")
        }
        builder = InterleavingTreeBuilder(numberOfSwitches, context.nodeCrashInfo)
        context.interleaving = root.chooseNextInterleaving(builder)
        addToFile("${hashCode()}@interleavings.txt") {
            it.appendLine(context.interleaving?.path.toString())
        }
        addToFile("${hashCode()}@logs.txt") {
            it.appendLine(context.events.toString())
        }
        addToFile("${hashCode()}@path.txt") {
            it.appendLine(context.taskManager.path.map { it.first }.toString())
        }
        //println()
        //println(context.interleaving?.path)
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