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

import org.jetbrains.kotlinx.lincheck.collectThreadDump
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.*
import org.jetbrains.kotlinx.lincheck.distributed.queue.*
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
    private val messageQueue: MessageQueue<Message> = when (testCfg.messageOrder) {
        SYNCHRONOUS -> SynchronousMessageQueue()
        FIFO -> FifoMessageQueue(testCfg.threads)
        ASYNCHRONOUS -> AsynchronousMessageQueue(testCfg.threads)
    }
    private val runnerHash = this.hashCode() // helps to distinguish this runner threads from others
    private val executor = FixedActiveThreadsExecutor(scenario.threads, runnerHash) // shoukd be closed in `close()`
    private lateinit var testInstances: Array<Node<Message>>
    private lateinit var messageBroker: MessageBroker
    private val failures = FailureStatistics(testCfg.threads, testCfg.maxNumberOfFailedNodes(testCfg.threads))
    private val messageId = AtomicInteger(0)

    private inner class MessageBroker() : Thread("MessageBroker") {
        override fun run() {
            while (runningStatus == RunStatus.RUNNING) {
                val message = messageQueue.get()
                if (message == null) {
                    cntNullGet++
                    continue
                }
                deliver(message)
            }
            while (runningStatus == RunStatus.GRACE_PERIOD) {
                val msg = messageQueue.getTillEmpty()
                if (msg == null) {
                    cntNullGet++
                    runningStatus = RunStatus.STOPPED
                } else {
                    deliver(msg)
                }
            }
        }

        private fun deliver(message: MessageSentEvent<Message>) {
            if (failures[message.receiver]) {
                return
            }
            //println("[${message.receiver}]: Received message ${message.message} from process ${message.sender}")
            events.put(MessageReceivedEvent(message.message, message.sender, message.receiver, message.id))
            try {
                testInstances[message.receiver].onMessage(message.message, message.sender)
            } catch (e: NodeFailureException) {
            }
        }
    }

    enum class RunStatus { RUNNING, STOPPED, GRACE_PERIOD }

    @Volatile
    private var runningStatus = RunStatus.STOPPED

    private lateinit var testThreadExecutions: Array<TestThreadExecution>

    private val events = LinkedBlockingQueue<Event>()

    private val probability = Probability(testCfg, testCfg.threads)

    private val logs = Array(testCfg.threads) { mutableListOf<Log>() }

    @Volatile
    private var isClosed = false


    private val environments: Array<Environment<Message, Log>> = Array(testCfg.threads) {
        EnvironmentImpl(it, testCfg.threads)
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
        events.clear()
        messageId.set(0)
        logs.forEach { it.clear() }
        check(messageQueue.get() == null)
        failures.clear()
        testInstances = Array(testCfg.threads) {
            testClass.getConstructor(Environment::class.java).newInstance(environments[it]) as Node<Message>
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
        runningStatus = RunStatus.RUNNING
        //messageBroker = MessageBroker()
        messageBroker = MessageBroker()
        messageBroker.start()
    }

    override fun constructStateRepresentation(): String {
        var res = "EXECUTION HISTORY\n"
        for (e in events) {
            res += e.toString() + "\n"
        }
        res += "NODE STATES\n"
        for (testInstance in testInstances) {
            res += stateRepresentationFunction?.let { getMethod(testInstance, it) }
                ?.invoke(testInstance) as String? + '\n'
        }
        return res
    }

    override fun run(): InvocationResult {
        reset()
        try {
            executor.submitAndAwait(testThreadExecutions, 200000)
        } catch (e: ExecutionException) {
            println("Execution exception")
            runningStatus = RunStatus.STOPPED
            messageBroker.join()
            return UnexpectedExceptionInvocationResult(e.cause!!)
        } catch (e: TimeoutException) {
            runningStatus = RunStatus.STOPPED
            messageBroker.join()
            events.forEach { println(it) }
            val threadDump = collectThreadDump(this)
            return DeadlockInvocationResult(threadDump)
        }
        runningStatus = RunStatus.GRACE_PERIOD
        messageBroker.join()
        //println("Joined")
        //while (runningStatus != RunStatus.STOPPED) {}

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

    override fun close() {
        super.close()
        runningStatus = RunStatus.STOPPED
        isClosed = true
        executor.close()
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

    override fun onFailure(iThread: Int, e: Throwable) {
        if (e is NodeFailureException && testCfg.supportRecovery) {

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
            get() = if (runningStatus == RunStatus.STOPPED) this@DistributedRunner.events.toList() else emptyList()

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
