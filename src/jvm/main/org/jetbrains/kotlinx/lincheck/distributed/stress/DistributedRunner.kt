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
import kotlinx.coroutines.sync.Semaphore
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@Volatile
var cntNullGet: Long = 0

@Volatile
private var consumedCPU = System.currentTimeMillis().toInt()

fun consumeCPU(tokens: Int) {
    var t = consumedCPU // volatile read
    for (i in tokens downTo 1)
        t += (t * 0x5DEECE66DL + 0xBL + i.toLong() and 0xFFFFFFFFFFFFL).toInt()
    if (t == 42)
        consumedCPU += t
}

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
        debugLogs.put(s)
        //println(s)
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
    private lateinit var testInstances: Array<Node<Message>>
    private lateinit var executorContext : NodeExecutorContext
    private lateinit var executors : Array<NodeExecutor>
    private val environments: Array<Environment<Message, Log>> = Array(testCfg.threads) {
        EnvironmentImpl(it, testCfg.threads)
    }
    private lateinit var testNodeExecutions: Array<TestNodeExecution>
    private val incomeMessages: Array<Channel<MessageSentEvent<Message>>> = Array(testCfg.threads) { Channel { } }
    private val failureNotifications: Array<Channel<Int>> = Array(testCfg.threads) { Channel { } }
    private val failures = FailureStatistics(testCfg.threads, testCfg.maxNumberOfFailedNodes(testCfg.threads))
    private val messageId = AtomicInteger(0)
    private var events = FastQueue<Event>()
    private val probability = Probability(testCfg, testCfg.threads)
    private val logs = Array(testCfg.threads) { mutableListOf<Log>() }

    @Volatile
    private var runningStatus = ITERATION_FINISHED
    private var exception: Throwable? = null
    private var jobs = mutableListOf<Job>()

    private suspend fun handleNodeFailure(iNode : Int, f : suspend () -> Unit) {
        try {
            f()
        } catch (_: NodeFailureException) {
            onNodeFailure(iNode)
        }
    }

    private suspend fun receiveMessages(i: Int) {
        while (runningStatus != ITERATION_FINISHED) {
            logMessage(LogLevel.ALL_EVENTS) {
                "Receiving message for node $i..."
            }
            val m = incomeMessages[i].receive()
            if (!failures[i]) {
                logMessage(LogLevel.MESSAGES) {
                    "[$i]: Received message ${m.id}  ${m.message} from node ${m.sender}"
                }
                events.put(MessageReceivedEvent(m.message, sender = m.sender, receiver = m.receiver, id = m.id))
                GlobalScope.launch(executors[i].asCoroutineDispatcher()) {
                    handleNodeFailure(i) {
                        testInstances[i].onMessage(m.message, m.sender)
                    }
                }
            }
           // withProbability(CONTEXT_SWITCH_PROBABILITY) { yield() }
        }
    }


    private suspend fun receiveUnavailableNodes(i: Int) {
        logMessage(LogLevel.ALL_EVENTS) {
            "Receiving failed nodes for node $i..."
        }
        while (runningStatus != ITERATION_FINISHED) {
            val node = failureNotifications[i].receive()
            if (!failures[i]) {
                GlobalScope.launch {
                    testInstances[i].onNodeUnavailable(node)
                }
            }
            withProbability(CONTEXT_SWITCH_PROBABILITY) { yield() }
        }
    }

    override fun initialize() {
        super.initialize()
        check(scenario.parallelExecution.size == testCfg.threads) {
            "Parallel execution size is ${scenario.parallelExecution.size}"
        }

        testNodeExecutions = Array(testCfg.threads) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
        testNodeExecutions.forEach { it.allTestNodeExecutions = testNodeExecutions }
    }

    private fun reset() {
        debugLogs = FastQueue()
        events = FastQueue()
        jobs.clear()
        messageId.set(0)
        logs.forEach { it.clear() }
        failures.clear()
        testInstances = Array(testCfg.threads) {
            testClass.getConstructor(Environment::class.java).newInstance(environments[it]) as Node<Message>
        }
        executorContext = NodeExecutorContext(testCfg.threads * 3, testCfg.threads + 1)
        executors = Array(testCfg.threads)  {
            NodeExecutor(it, executorContext, runnerHash)
        }
        val useClocks = Random.nextBoolean()
        testNodeExecutions.forEachIndexed { t, ex ->
            ex.testInstance = testInstances[t]
            val threads = testCfg.threads
            val actors = scenario.parallelExecution[t].size
            ex.useClocks = useClocks
            ex.curClock = 0
            ex.clocks = Array(actors) { emptyClockArray(threads) }
            ex.results = arrayOfNulls(actors)
        }
        runningStatus = ITERATION_STARTED
    }

    override fun constructStateRepresentation(): String {
        var res = "NODE STATES\n"
        for (testInstance in testInstances) {
            res += stateRepresentationFunction?.let { getMethod(testInstance, it) }
                ?.invoke(testInstance) as String? + '\n'
        }
        return res
    }

    override fun run(): InvocationResult {
        try {
            reset()
            for (i in 0 until testCfg.threads) {
                jobs.add(GlobalScope.launch(executors[i].asCoroutineDispatcher()) {
                    launchNode(
                        i
                    )
                })
                jobs.add(GlobalScope.launch(executors[i].asCoroutineDispatcher()) {
                    receiveMessages(i)
                })
                jobs.add(GlobalScope.launch(executors[i].asCoroutineDispatcher()) {
                    receiveUnavailableNodes(i)
                })
            }
            runBlocking {
                withTimeout(20000) {
                    executorContext.semaphore.acquire()
                }
            }

            runningStatus = ITERATION_FINISHED
            jobs.forEach { it.cancel() }
            executors.forEach { it.shutdown() }

            if (exception != null) {
                return UnexpectedExceptionInvocationResult(exception!!)
            }

            testInstances.forEach {
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
            //println("Total get operations=${getCnt.value}, null results=${getNull.value}, not null results=${getCnt.value - getNull.value}")
            val parallelResultsWithClock = testNodeExecutions.map { ex ->
                ex.results.zip(ex.clocks).map { ResultWithClock(it.first!!, HBClock(it.second)) }
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
            testNodeExecutions.forEachIndexed { i, t ->
                println("Results $i")
                scenario.parallelExecution[i].forEach { println(it.arguments) }
                t.results.forEach { println(it) }
            }
            System.out.flush()
            throw e
        }
    }

    private fun collectThreadDump() = Thread.getAllStackTraces().filter { (t, _) ->
        t.name.startsWith("NodeExecutorThread")
    }

    private suspend fun launchNode(iNode: Int) {
        val scenarioSize = scenario.parallelExecution[iNode].size
        for (i in 0 until scenarioSize) {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$iNode]: Operation $i"
            }
            val actor = scenario.parallelExecution[iNode][i]
            try {
                val res = testNodeExecutions[iNode].runOperation(i)
                testNodeExecutions[iNode].results[i] = if (actor.method.returnType == Void.TYPE) {
                    if (actor.isSuspendable) {
                        SuspendedVoidResult
                    } else {
                        VoidResult
                    }
                } else {
                    createLincheckResult(res)
                }
            } catch (_: NodeFailureException) {
                testNodeExecutions[iNode].results[i] = createNodeFailureResult()
                onNodeFailure(iNode)
                if (!testCfg.supportRecovery) {
                    (i + 1 until scenarioSize).forEach { testNodeExecutions[iNode].results[it] = NoResult }
                    return
                }
            } catch (e: Throwable) {
                if (e.javaClass in actor.handledExceptions) {
                    testNodeExecutions[iNode].results[i] = createExceptionResult(e.javaClass)
                } else {
                    onFailure(iNode, e)
                }
            }
            logMessage(LogLevel.ALL_EVENTS) {
                "[$iNode]: Op $i ${testNodeExecutions[iNode].results[i]}"
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
        jobs.forEach { it.cancel() }
        executorContext.semaphore.release()
    }

    private suspend fun onNodeFailure(iThread: Int) {
        failureNotifications.filterIndexed { index, _ -> index != iThread }.forEach { it.send(iThread) }
        // testInstances.filterIndexed { index, _ -> !failures[index] }.forEach { it.onNodeUnavailable(iThread) }
        if (testCfg.supportRecovery) {
            delay(1)
            testInstances[iThread] =
                testClass.getConstructor(Environment::class.java).newInstance(environments[iThread]) as Node<Message>
            events.put(ProcessRecoveryEvent(iThread))
            failures[iThread] = false
            if (testClass is RecoverableNode<*, *>) {
                (testInstances[iThread] as RecoverableNode<Message, Log>).recover(logs[iThread])
            } else {
                testInstances[iThread].recover()
            }
        }
    }

    private inner class EnvironmentImpl(
        override val nodeId: Int,
        override val numberOfNodes:
        Int
    ) : Environment<Message, Log> {
        override suspend fun send(message: Message, receiver: Int) {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$nodeId]: Sending $message to $receiver"
            }
            check(receiver != nodeId) {
                "Cannot send messages to itself"
            }
            if (failures[nodeId]) {
                throw NodeFailureException(nodeId)
            }
            val messageSentEvent =
                MessageSentEvent(message, sender = nodeId, receiver = receiver, id = messageId.getAndIncrement())
            this@DistributedRunner.events.put(messageSentEvent)
            if (probability.nodeFailed(failures)) {
                logMessage(LogLevel.MESSAGES) {
                    "[$nodeId]: Failed"
                }
                failures[nodeId] = true
                this@DistributedRunner.events.put(ProcessFailureEvent(nodeId))
                throw NodeFailureException(nodeId)
            }
            if (!probability.messageIsSent()) {
                return
            }
            val duplicates = probability.duplicationRate()
            logMessage(LogLevel.ALL_EVENTS) {
                "[$nodeId]: Before sending message $message to node $receiver"
            }
            for (i in 0 until duplicates) {
                incomeMessages[receiver].send(messageSentEvent)
                logMessage(LogLevel.MESSAGES) {
                    "[$nodeId]: Send message ${messageSentEvent.id} $message to node $receiver"
                }
            }
        }

        override fun sendLocal(message: Message) {
            this@DistributedRunner.events.put(LocalMessageSentEvent(message, nodeId))
        }

        override val events: List<Event>
            get() = emptyList()

        override fun getAddress(cls: Class<out Node<Message>>, i: Int): Int {
            TODO("Not yet implemented")
        }

        override fun getNumberOfNodeType(cls: Class<out Node<Message>>): Int {
            TODO("Not yet implemented")
        }

        override val log: MutableList<Log>
            get() = logs[nodeId]
    }
}
