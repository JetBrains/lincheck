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

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.createLincheckResult
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.*
import org.jetbrains.kotlinx.lincheck.distributed.queue.*
import org.jetbrains.kotlinx.lincheck.distributed.stress.RunningStatus.*
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.getMethod
import org.jetbrains.kotlinx.lincheck.runner.*
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
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

enum class LogLevel { NO_OUTPUT, ITERATION_NUMBER, MESSAGES, ALL_EVENTS }

val logLevel = LogLevel.ALL_EVENTS

fun logMessage(givenLogLevel: LogLevel, f: () -> Unit) {
    if (logLevel >= givenLogLevel) {
        f()
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

    private lateinit var testInstances: Array<Node<Message>>
    lateinit var dispatchers: Array<CoroutineDispatcher>
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
    private var runningStatus = ITERATION_FINISHED
    private lateinit var semaphore: Semaphore
    private var exception: Throwable? = null
    private var jobs = mutableListOf<Job>()

    private suspend fun receiveMessages(i: Int) {
        while (true) {
            val m = incomeMessages[i].receive()
            if (!failures[i]) {
                logMessage(LogLevel.MESSAGES) {
                    println("[$i]: Received message ${m.message} from node ${m.sender}")
                }
                events.put(MessageReceivedEvent(m.message, sender = m.sender, receiver = m.receiver, id = m.id))
                testInstances[i].onMessage(m.message, m.sender)
            }
            withProbability(CONTEXT_SWITCH_PROBABILITY) { yield() }
        }
    }


    private suspend fun receiveUnavailableNodes(i: Int) {
        while (true) {
            val node = failureNotifications[i].receive()
            if (!failures[i]) {
                testInstances[i].onNodeUnavailable(node)
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
        events = FastQueue()
        jobs.clear()
        messageId.set(0)
        logs.forEach { it.clear() }
        failures.clear()
        testInstances = Array(testCfg.threads) {
            testClass.getConstructor(Environment::class.java).newInstance(environments[it]) as Node<Message>
        }
        val executionFinishedCounter = AtomicInteger(0)
        semaphore = Semaphore(testCfg.threads + 1, testCfg.threads + 1)
        dispatchers = Array(testCfg.threads) {
            NodeExecutor(executionFinishedCounter, testCfg.threads, it, semaphore).asCoroutineDispatcher()
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
        reset()
        for (i in 0 until testCfg.threads) {
            jobs.add(GlobalScope.launch(dispatchers[i]) {
                receiveMessages(i)
            })
            jobs.add(GlobalScope.launch(dispatchers[i]) {
                receiveUnavailableNodes(i)
            })
        }
        testNodeExecutions.forEachIndexed { index, _ ->
            jobs.add(GlobalScope.launch(dispatchers[index]) {
                launchNode(
                    index
                )
            })
        }
        runBlocking { semaphore.acquire() }
        if (exception != null) {
            return UnexpectedExceptionInvocationResult(exception!!)
        }
        runningStatus = ITERATION_FINISHED
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

        val parallelResultsWithClock = testNodeExecutions.map { ex ->
            ex.results.zip(ex.clocks).map { ResultWithClock(it.first!!, HBClock(it.second)) }
        }
        val results = ExecutionResult(
            emptyList(), null, parallelResultsWithClock, constructStateRepresentation(),
            emptyList(), null
        )
        return CompletedInvocationResult(results)
    }

    private suspend fun launchNode(iNode: Int) {
        val scenarioSize = scenario.parallelExecution[iNode].size
        for (i in 0 until scenarioSize) {
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
                println("[$iNode]: Op $i ${testNodeExecutions[iNode].results[i]}")
            }
        }
    }

    override fun onFailure(iThread: Int, e: Throwable) {
        super.onFailure(iThread, e)
        exception = e
        jobs.forEach { it.cancel() }
        //semaphore.release()
        logMessage(LogLevel.ALL_EVENTS) {
            println("Exception")
            e.printStackTrace()
        }
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
            if (failures[nodeId]) {
                throw NodeFailureException(nodeId)
            }
            val messageSentEvent =
                MessageSentEvent(message, sender = nodeId, receiver = receiver, id = messageId.getAndIncrement())
            this@DistributedRunner.events.put(messageSentEvent)
            if (probability.nodeFailed(failures)) {
                logMessage(LogLevel.MESSAGES) {
                    println("[$nodeId]: Failed")
                }
                failures[nodeId] = true
                this@DistributedRunner.events.put(ProcessFailureEvent(nodeId))
                throw NodeFailureException(nodeId)
            }
            if (!probability.messageIsSent()) {
                return
            }
            val duplicates = probability.duplicationRate()
            for (i in 0 until duplicates) {
                logMessage(LogLevel.MESSAGES) {
                    println("[$nodeId]: Send message $message to node $receiver")
                }
                incomeMessages[receiver].send(messageSentEvent)
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
