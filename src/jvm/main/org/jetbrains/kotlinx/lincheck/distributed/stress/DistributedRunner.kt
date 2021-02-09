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

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.scheduling.ExperimentalCoroutineDispatcher
import org.jetbrains.kotlinx.lincheck.collectThreadDump
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

    private val messageQueue: MessageQueue<Message> = when (testCfg.messageOrder) {
        SYNCHRONOUS -> SynchronousMessageQueue()
        FIFO -> FifoMessageQueue(testCfg.threads)
        ASYNCHRONOUS -> AsynchronousMessageQueue(testCfg.threads)
    }
    private lateinit var testInstances: Array<Node<Message>>
    private lateinit var dispatchers: Array<CoroutineDispatcher>
    private val environments: Array<Environment<Message, Log>> = Array(testCfg.threads) {
        EnvironmentImpl(it, testCfg.threads)
    }
    private lateinit var testThreadExecutions: Array<TestThreadExecution>
    private val incomeMessages: Array<Channel<MessageSentEvent<Message>>> = Array(testCfg.threads) { Channel { } }
    private val failureNotifications: Array<Channel<Int>> = Array(testCfg.threads) { Channel { } }
    private val failures = FailureStatistics(testCfg.threads, testCfg.maxNumberOfFailedNodes(testCfg.threads))
    private val messageId = AtomicInteger(0)
    private var events = FastQueue<Event>()
    private val probability = Probability(testCfg, testCfg.threads)
    private val logs = Array(testCfg.threads) { mutableListOf<Log>() }
    private var runningStatus = ITERATION_FINISHED

    private suspend fun receiveMessages(i: Int) {
        while (true) {
            val m = incomeMessages[i].receive()
            events.put(MessageReceivedEvent(m.message, sender = m.sender, receiver = m.receiver, id = m.id))
            testInstances[i].onMessage(m.message, m.sender)
            withProbability(CONTEXT_SWITCH_PROBABILITY) { yield() }
        }
    }

    private suspend fun receiveUnavailableNodes(i: Int) {
        while (true) {
            val node = failureNotifications[i].receive()
            testInstances[i].onNodeUnavailable(node)
            withProbability(CONTEXT_SWITCH_PROBABILITY) { yield() }
        }
    }

    override fun initialize() {
        super.initialize()
        check(scenario.parallelExecution.size == testCfg.threads) {
            "Parallel execution size is ${scenario.parallelExecution.size}"
        }
        testThreadExecutions = Array(testCfg.threads) { t ->
            TestThreadExecutionGenerator.create(this, t, scenario.parallelExecution[t], null, false)
        }
        testThreadExecutions.forEach { it.allThreadExecutions = testThreadExecutions }
    }

    private fun reset() {
        events = FastQueue()
        messageId.set(0)
        logs.forEach { it.clear() }
        check(messageQueue.get() == null)
        failures.clear()
        testInstances = Array(testCfg.threads) {
            testClass.getConstructor(Environment::class.java).newInstance(environments[it]) as Node<Message>
        }
        val executionFinishedCounter = atomic(0)
        dispatchers = Array(testCfg.threads) {
            NodeExecutor(executionFinishedCounter, testCfg.threads).asCoroutineDispatcher()
        }

        val useClocks = Random.nextBoolean()
        testThreadExecutions.forEachIndexed { t, ex ->
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
            GlobalScope.launch(dispatchers[i]) {
                receiveMessages(i)
            }
            GlobalScope.launch(dispatchers[i]) {
                receiveUnavailableNodes(i)
            }
        }
        testThreadExecutions.forEach { it.run() }
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

        val parallelResultsWithClock = testThreadExecutions.map { ex ->
            ex.results.zip(ex.clocks).map { ResultWithClock(it.first, HBClock(it.second)) }
        }
        val results = ExecutionResult(
            emptyList(), null, parallelResultsWithClock, constructStateRepresentation(),
            emptyList(), null
        )
        return CompletedInvocationResult(results)
    }

    fun onNodeFailure(iThread: Int) {
        testInstances.filterIndexed { index, _ -> !failures[index] }.forEach { it.onNodeUnavailable(iThread) }
        if (testCfg.supportRecovery) {
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
        override fun send(message: Message, receiver: Int) {
            if (failures[nodeId]) {
                throw NodeFailureException(nodeId)
            }
            val messageSentEvent =
                MessageSentEvent(message, sender = nodeId, receiver = receiver, id = messageId.getAndIncrement())
            this@DistributedRunner.events.put(messageSentEvent)
            if (probability.nodeFailed(failures)) {
                failures[nodeId] = true
                this@DistributedRunner.events.put(ProcessFailureEvent(nodeId))
                throw NodeFailureException(nodeId)
            }
            if (!probability.messageIsSent()) {
                return
            }
            val duplicates = probability.duplicationRate()
            for (i in 0 until duplicates) {
                messageQueue.put(messageSentEvent)
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
