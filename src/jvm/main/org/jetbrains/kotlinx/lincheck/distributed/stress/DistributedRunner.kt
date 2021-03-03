/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.distributed.stress

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.*
import org.jetbrains.kotlinx.lincheck.distributed.queue.*
import org.jetbrains.kotlinx.lincheck.distributed.stress.RunningStatus.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.CancellationException
import kotlin.random.Random

@Volatile
var cntNullGet: Long = 0

internal class NodeFailureException(val nodeId: Int) : Exception()

inline fun withProbability(probability: Double, func: () -> Unit) {
    val rand = Random.nextDouble(0.0, 1.0)
    if (rand <= probability) {
        func()
    }
}

private enum class RunningStatus { ITERATION_STARTED, ITERATION_FINISHED }

enum class LogLevel { NO_OUTPUT, ITERATION_NUMBER, MESSAGES, ALL_EVENTS, KICKED }

val logLevel = LogLevel.NO_OUTPUT
var debugLogs = FastQueue<String>()

fun logMessage(givenLogLevel: LogLevel, f: () -> String) {
    if (logLevel >= givenLogLevel) {
        val s = Thread.currentThread().name + " " + f()
        //debugLogs.put(s)
        println(s)
        System.out.flush()
    }
}

open class DistributedRunner<Message, Log>(
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
    companion object {
        const val CONTEXT_SWITCH_PROBABILITY = 0.3
    }

    private val runnerHash = this.hashCode()
    private val context = DistributedRunnerContext(testCfg, scenario)
    private lateinit var executorContext: NodeExecutorContext
    private lateinit var executors: Array<NodeExecutor>
    private lateinit var dispatchers: Array<CoroutineDispatcher>
    private var environments: Array<Environment<Message, Log>> = Array(testCfg.threads) {
        EnvironmentImpl(context, it)
    }

    @Volatile
    private var runningStatus = ITERATION_FINISHED
    private var exception: Throwable? = null

    private val numberOfNodes = context.addressResolver.totalNumberOfNodes

    private suspend fun handleNodeFailure(iNode: Int, f: suspend () -> Unit) {
        if (context.failureInfo[iNode]) return
        try {
            f()
        } catch (_: NodeFailureException) {
            onNodeFailure(iNode)
        }
    }

    private suspend fun receiveMessages(
        i: Int,
        channel: Channel<MessageSentEvent<Message>>,
        testInstance: Node<Message>
    ) {
        try {
            while (true) {
                try {
                    logMessage(LogLevel.ALL_EVENTS) {
                        "[$i]: Receiving message for node $i..."
                    }
                    val m =
                        try {
                            channel.receive()
                        } catch(_ : CancellationException) {
                            continue
                        }

                    if (context.failureInfo[i]) return
                    val aaa = if (i == m.sender || i != m.receiver) {
                        "AAAAAAAAAAAAAAAAAAAA"
                    } else {
                        ""
                    }
                    logMessage(LogLevel.MESSAGES) {
                        "[$i]: $aaa Received $m ${channel.hashCode()}"
                    }
                    context.incClock(i)
                    val clock = context.maxClock(i, m.clock)
                    context.events[i].add(
                        MessageReceivedEvent(
                            m.message,
                            sender = m.sender,
                            receiver = m.receiver,
                            id = m.id,
                            clock = clock
                        )
                    )
                    GlobalScope.launch(executors[i].asCoroutineDispatcher()) {
                        handleNodeFailure(i) {
                            testInstance.onMessage(m.message, m.sender)
                        }
                    }
                    withProbability(CONTEXT_SWITCH_PROBABILITY) { yield() }
                } catch (_: CancellationException) {
                    logMessage(LogLevel.ALL_EVENTS) {
                        "[$i]: Caught cancellation exception"
                    }
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // logMessage("")
        }
    }


    private suspend fun receiveUnavailableNodes(
        i: Int, channel: Channel<Int>,
        testInstance: Node<Message>
    ) {
        try {
            logMessage(LogLevel.ALL_EVENTS) {
                "Receiving failed nodes for node $i..."
            }
            while (runningStatus != ITERATION_FINISHED) {
                val node = channel.receive()
                if (context.failureInfo[i]) return
                context.incClock(i)
                GlobalScope.launch(executors[i].asCoroutineDispatcher()) {
                    handleNodeFailure(i) {
                        testInstance.onNodeUnavailable(node)
                    }
                }
                withProbability(CONTEXT_SWITCH_PROBABILITY) { yield() }
            }
        } catch (_: ClosedReceiveChannelException) {
        }
    }

    override fun initialize() {
        super.initialize()
        context.testNodeExecutions = Array(context.addressResolver.nodesWithScenario) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
    }

    private fun reset() {
        context.reset()
        environments = Array(testCfg.threads) {
            EnvironmentImpl(context, it)
        }
        context.testInstances = Array(numberOfNodes) {
            context.addressResolver[it].getConstructor(Environment::class.java)
                .newInstance(environments[it]) as Node<Message>
        }
        executorContext = NodeExecutorContext(
            numberOfNodes * 3,
            numberOfNodes + 1
        )

        executors = Array(numberOfNodes) {
            NodeExecutor(it, executorContext, runnerHash)
        }
        dispatchers = Array(numberOfNodes) {
            executors[it].asCoroutineDispatcher()
        }
        context.testNodeExecutions.forEachIndexed { t, ex ->
            ex.testInstance = context.testInstances[t]
            val actors = scenario.parallelExecution[t].size
            ex.results = arrayOfNulls(actors)
        }
        runningStatus = ITERATION_STARTED
    }

    override fun constructStateRepresentation(): String {
        var res = "NODE STATES\n"
        //TODO check for not null
        for (testInstance in context.testInstances) {
            res += stateRepresentationFunction?.let { getMethod(testInstance, it) }
                ?.invoke(testInstance) as String? + '\n'
        }

        res += "MESSAGE HISTORY\n"
        context.events.forEachIndexed { index, mutableList ->
            mutableList.map { "[$index]: $it\n" }.forEach { res += it }
        }
        res += "LOGS\n"
        environments.forEachIndexed { index, environment ->
            environment.log.map { "[$index]: $it\n" }.forEach { res += it }
        }
        return res
    }

    override fun run(): InvocationResult {
        try {
            reset()
            //TODO: Fix this for different types of nodes
            for (i in 0 until numberOfNodes) {
                GlobalScope.launch(executors[i].asCoroutineDispatcher()) {
                    launchNode(
                        i
                    )
                }
                GlobalScope.launch(executors[i].asCoroutineDispatcher()) {
                    receiveMessages(i, context.incomeMessages[i], context.testInstances[i])
                }
                GlobalScope.launch(executors[i].asCoroutineDispatcher()) {
                    receiveUnavailableNodes(i, context.failureNotifications[i], context.testInstances[i])
                }
            }
            runBlocking {
                executorContext.semaphore.acquire()
                logMessage(LogLevel.ALL_EVENTS) {
                    "Semaphore aqcuired"
                }
                //delay(10000)
            }
            executors.forEach { it.shutdown() }
            environments.forEach { (it as EnvironmentImpl).isFinished = true }

            if (exception != null) {
                //println(constructStateRepresentation())
                throw exception!!
                //return UnexpectedExceptionInvocationResult(exception!!)
            }

            context.testInstances.forEach {
                executeValidationFunctions(
                    it,
                    validationFunctions
                ) { functionName, exception ->
                    val s = ExecutionScenario(
                        scenario.initExecution,
                        scenario.parallelExecution,
                        emptyList()
                    )
                    println(constructStateRepresentation())
                    return ValidationFailureInvocationResult(s, functionName, exception)
                }
            }

            //TODO: handle null results
            //println("Total get operations=${getCnt.value}, null results=${getNull.value}, not null results=${getCnt.value - getNull.value}")
            val parallelResultsWithClock = context.testNodeExecutions.mapIndexed { i, ex ->
                //TODO add real vector clock
                // ex.results
                val fakeClock = Array(ex.results.size) {
                    IntArray(numberOfNodes)
                }
                ex.results.zip(fakeClock).map { ResultWithClock(it.first!!, HBClock(it.second)) }
            }
            val results = ExecutionResult(
                emptyList(), null, parallelResultsWithClock, constructStateRepresentation(),
                emptyList(), null
            )
            return CompletedInvocationResult(results)
        } catch (e: Throwable) {
            do {
                val l = debugLogs.poll()
                println(l)
            } while (l != null)
            context.testNodeExecutions.forEachIndexed { i, t ->
                println("Results $i")
                scenario.parallelExecution[i].forEach { println(it.arguments) }
                t.results.forEach { println("$it ${t.hashCode()}") }
            }
            System.out.flush()
            throw e
        }
    }

    private fun collectThreadDump() = Thread.getAllStackTraces().filter { (t, _) ->
        t is NodeExecutor.NodeTestThread && t.runnerHash == runnerHash
    }

    private suspend fun launchNode(iNode: Int) {
        handleNodeFailure(iNode) {
            val scenarioSize = scenario.parallelExecution[iNode].size
            while (context.actorIds[iNode] < scenarioSize) {
                val i = context.actorIds[iNode]
                logMessage(LogLevel.ALL_EVENTS) {
                    "[$iNode]: Operation $i"
                }
                val actor = scenario.parallelExecution[iNode][i]
                try {
                    val res = context.testNodeExecutions[iNode].runOperation(i)
                    context.testNodeExecutions[iNode].results[i] = if (actor.method.returnType == Void.TYPE) {
                        if (actor.isSuspendable) {
                            SuspendedVoidResult
                        } else {
                            VoidResult
                        }
                    } else {
                        createLincheckResult(res)
                    }
                    logMessage(LogLevel.ALL_EVENTS) {
                        "[$iNode]: Wrote result $i ${context.testNodeExecutions[iNode].hashCode()} ${context.testNodeExecutions[iNode].results[i]}"
                    }
                    context.actorIds[iNode]++
                } catch (e: Throwable) {
                    if (e is NodeFailureException) {
                        throw e
                    }
                    if (e.javaClass in actor.handledExceptions) {
                        context.testNodeExecutions[iNode].results[i] = createExceptionResult(e.javaClass)
                        context.actorIds[iNode]++
                    } else {
                        onFailure(iNode, e)
                        context.actorIds[iNode] = scenarioSize
                    }
                }
                logMessage(LogLevel.ALL_EVENTS) {
                    "[$iNode]: Op $i ${context.testNodeExecutions[iNode].results[i]}"
                }
            }
            logMessage(LogLevel.ALL_EVENTS) {
                "[$iNode]: Operations over"
            }
        }
    }

    override fun onFailure(iThread: Int, e: Throwable) {
        super.onFailure(iThread, e)
        println("[$iThread]: Unhandled exception $e")
        logMessage(LogLevel.ALL_EVENTS) {
            "Exception $e"
        }
        exception = e
        executorContext.semaphore.release()
    }

    private suspend fun onNodeFailure(iNode: Int) {
        logMessage(LogLevel.MESSAGES) {
            "[$iNode]: Process failed"
        }
        context.failureNotifications.filterIndexed { index, _ -> index != iNode }.forEach {
            try {
                it.send(iNode)
            } catch (_: ClosedSendChannelException) {
            }
        }
        context.incomeMessages[iNode].close()
        context.failureNotifications[iNode].close()
        context.events[iNode].add(ProcessFailureEvent(iNode, context.vectorClock[iNode].copyOf()))
        executors[iNode].lazyShutdown()
        (environments[iNode] as EnvironmentImpl).isFinished = true
        val scenarioSize = scenario.parallelExecution[iNode].size
        if (context.actorIds[iNode] < scenarioSize) {
            context.testNodeExecutions[iNode].results[context.actorIds[iNode]++] = createNodeFailureResult()
        }
        if (testCfg.supportRecovery) {
            executorContext.add(3)
            val logs = environments[iNode].log.toMutableList()
            context.incomeMessages[iNode] = Channel(UNLIMITED)
            context.failureNotifications[iNode] = Channel(UNLIMITED)
            environments[iNode] = EnvironmentImpl(context, iNode, logs)
            context.testInstances[iNode] =
                testClass.getConstructor(Environment::class.java).newInstance(environments[iNode]) as Node<Message>
            context.testNodeExecutions[iNode].testInstance = context.testInstances[iNode]
            executors[iNode] = NodeExecutor(iNode, executorContext, runnerHash)
            dispatchers[iNode] = executors[iNode].asCoroutineDispatcher()
            context.events[iNode].add(ProcessRecoveryEvent(iNode, context.vectorClock[iNode].copyOf()))
            GlobalScope.launch(dispatchers[iNode]) {
                receiveMessages(iNode, context.incomeMessages[iNode], context.testInstances[iNode])
            }
            GlobalScope.launch(dispatchers[iNode]) {
                receiveUnavailableNodes(iNode, context.failureNotifications[iNode], context.testInstances[iNode])
            }
            context.failureInfo.setRecovered(iNode)
            GlobalScope.launch(dispatchers[iNode]) {
                context.testInstances[iNode].recover()
                launchNode(iNode)
            }
        } else {

            (context.actorIds[iNode] until scenarioSize).forEach {
                context.testNodeExecutions[iNode].results[it] = createNodeFailureResult()
            }
            context.actorIds[iNode] = scenarioSize
        }
    }
}
