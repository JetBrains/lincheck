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

import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder.*
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.getMethod
import org.jetbrains.kotlinx.lincheck.runner.*
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random


@Volatile
private var consumedCPU = System.currentTimeMillis().toInt()

fun consumeCPU(tokens: Int) {
    var t = consumedCPU // volatile read
    for (i in tokens downTo 1)
        t += (t * 0x5DEECE66DL + 0xBL + i.toLong() and 0xFFFFFFFFFFFFL).toInt()
    if (t == 42)
        consumedCPU += t
}


/**
 * The interface for message queue. Different implementations provide different guarantees of message delivery order.
 */
internal interface MessageQueue<Message> {
    fun put(msg: MessageSentEvent<Message>)
    fun get(): MessageSentEvent<Message>?
    fun clear()
    fun isNotEmpty() : Boolean
}

internal class SynchronousMessageQueue<Message> : MessageQueue<Message> {
    private val messageQueue = LinkedBlockingQueue<MessageSentEvent<Message>>()
    override fun put(msg: MessageSentEvent<Message>) {
        messageQueue.add(msg)
    }

    override fun get(): MessageSentEvent<Message>? {
        return messageQueue.poll()
    }

    override fun clear() {
        messageQueue.clear()
    }

    override fun isNotEmpty() = messageQueue.isNotEmpty()
}

internal class FifoMessageQueue<Message>(numberOfNodes: Int) : MessageQueue<Message> {
    private val messageQueues = Array(numberOfNodes) {
        LinkedBlockingQueue<MessageSentEvent<Message>>()
    }

    override fun put(msg: MessageSentEvent<Message>) {
        messageQueues[msg.receiver].add(msg)
    }

    override fun get(): MessageSentEvent<Message>? {
        if (messageQueues.none { it.isNotEmpty() }) {
            return null
        }
        return messageQueues.filter { it.isNotEmpty() }.shuffled()[0].poll()
    }

    override fun clear() {
        messageQueues.forEach { it.clear() }
    }

    override fun isNotEmpty() = messageQueues.any { it.isNotEmpty() }
}

internal class AsynchronousMessageQueue<Message>(private val numberOfNodes: Int) : MessageQueue<Message> {
    private val messageQueues = Array(numberOfNodes) {
        LinkedBlockingQueue<MessageSentEvent<Message>>()
    }

    override fun put(msg: MessageSentEvent<Message>) {
        val queueToPut = Random.nextInt(numberOfNodes)
        messageQueues[queueToPut].add(msg)
    }

    override fun get(): MessageSentEvent<Message>? {
        if (messageQueues.none { it.isNotEmpty() }) {
            return null
        }
        return messageQueues.filter { it.isNotEmpty() }.shuffled()[0].poll()
    }

    override fun clear() {
        messageQueues.forEach { it.clear() }
    }

    override fun isNotEmpty() = messageQueues.any { it.isNotEmpty() }
}

internal class NodeFailureException(val nodeId: Int) : Exception()

open class DistributedRunner<Message>(strategy: DistributedStrategy<Message>,
                                      val testCfg: DistributedCTestConfiguration<Message>,
                                      testClass: Class<*>,
                                      validationFunctions: List<Method>,
                                      stateRepresentationFunction: Method?) : Runner(
        strategy, testClass,
        validationFunctions,
        stateRepresentationFunction) {
    private val messageQueue: MessageQueue<Message> = when (testCfg.messageOrder) {
        SYNCHRONOUS -> SynchronousMessageQueue()
        FIFO -> FifoMessageQueue(testCfg.threads)
        ASYNCHRONOUS -> AsynchronousMessageQueue(testCfg.threads)
    }
    private lateinit var testInstances: Array<Node<Message>>
    private lateinit var messageCounts: MutableList<AtomicInteger>
    private lateinit var messageBroker: MessageBroker
    private val failedProcesses = Array(testCfg.threads) { AtomicBoolean(false) }
    private val messageId = AtomicInteger(0)

    private inner class MessageBroker : Thread() {
        override fun run() {
            while (isRunning || messageQueue.isNotEmpty()) {
                val messageWrapper = messageQueue.get() ?: continue
                if (failedProcesses[messageWrapper.receiver].get()) {
                    continue
                }
                //println("[${messageWrapper.receiver}]: Received message ${messageWrapper.message} from process ${messageWrapper.sender}")
                events.put(MessageReceivedEvent(messageWrapper.message, messageWrapper.sender, messageWrapper.receiver, messageWrapper.id))
                testInstances[messageWrapper.receiver].onMessage(messageWrapper.message, messageWrapper.sender)
            }
        }
    }

    @Volatile
    private var isRunning = false

    private lateinit var testThreadExecutions: Array<TestThreadExecution>

    private val executor = newFixedThreadPool(testCfg.threads)

    private val events = LinkedBlockingQueue<Event>()

    private val environments: Array<Environment<Message>> = Array(testCfg.threads) {
        EnvironmentImpl(it, testCfg.threads)
    }

    override fun initialize() {
        super.initialize()
        check(scenario.parallelExecution.size == testCfg.threads) {
            "Parallel execution size is ${scenario.parallelExecution.size}"
        }
        testThreadExecutions = Array(testCfg.threads) { t ->
            // println(scenario.parallelExecution[t].size)
            TestThreadExecutionGenerator.create(this, t, scenario.parallelExecution[t], null, false)
        }
        testThreadExecutions.forEach { it.allThreadExecutions = testThreadExecutions }
        messageBroker = MessageBroker()
    }

    private fun reset() {
        events.clear()
        messageId.set(0)
        messageQueue.clear()
        failedProcesses.fill(AtomicBoolean(false))
        testInstances = Array(testCfg.threads) {
            testClass.getConstructor(Environment::class.java).newInstance(environments[it]) as Node<Message>
        }
        messageCounts = Collections.synchronizedList(Array(testCfg.threads) {
            AtomicInteger(0)
        }.asList())

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
        isRunning = true
        messageBroker = MessageBroker()
        messageBroker.start()
    }

    override fun constructStateRepresentation(): String {
        var res = "NODE STATES\n"
        for (testInstance in testInstances) {
            res += stateRepresentationFunction?.let { getMethod(testInstance, it) }?.invoke(testInstance) as String? + '\n'
        }
        return res
    }

    override fun run(): InvocationResult {
        reset()
        testThreadExecutions.map { executor.submit(it) }.forEachIndexed { i, future ->
            try {
                future.get(20, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                return UnexpectedExceptionInvocationResult(e.cause!!)
            } catch (e: TimeoutException) {
                isRunning = false
                messageBroker.join()
                val threadDump = Thread.getAllStackTraces().filter { (t, _) -> t is FixedActiveThreadsExecutor.TestThread }
                return DeadlockInvocationResult(threadDump)
            }
        }
        isRunning = false
        messageBroker.join()
        val parallelResultsWithClock = testThreadExecutions.filterIndexed { i, _ -> !failedProcesses[i].get() }.map { ex ->
            ex.results.zip(ex.clocks).map { ResultWithClock(it.first, HBClock(it.second)) }
        }
        testInstances.forEach {
            executeValidationFunctions(it,
                    validationFunctions) { functionName, exception ->
                val s = ExecutionScenario(
                        scenario.initExecution,
                        scenario.parallelExecution,
                        emptyList()
                )
                println(constructStateRepresentation())
                return ValidationFailureInvocationResult(s, functionName, exception)
            }
        }
        messageCounts.forEachIndexed { t, c ->
            if (c.get() > testCfg.messagePerProcess) {
                return InvariantsViolatedInvocationResult(scenario,
                        "Process $t send more messages than expected (actual count is" +
                                " ${c.get()})")
            }
        }
        val totalNumberOfMessages = messageCounts.fold(0) { acc, c ->
            acc + c.get()
        }
        if (totalNumberOfMessages > testCfg.totalMessageCount) {
            return InvariantsViolatedInvocationResult(scenario,
                    "Total number of messages is more than " +
                            "expected (actual is $totalNumberOfMessages)")
        }
        val results = ExecutionResult(emptyList(), null, parallelResultsWithClock, constructStateRepresentation(),
                emptyList(), null)
        return CompletedInvocationResult(results)
    }

    override fun close() {
        super.close()

        executor.shutdown()
        println("All")
    }

    private inner class EnvironmentImpl(override val nodeId: Int,
                                        override val numberOfNodes:
                                        Int) : Environment<Message> {

        override fun setTimer(timer: String, time: Int, timeUnit: TimeUnit) {
            TODO("Not yet implemented")
        }

        override fun cancelTimer(timer: String) {
            TODO("Not yet implemented")
        }

        override fun send(message: Message, receiver: Int) {
            //println("[$nodeId]: Sent message $message to process $receiver")
            val messageSentEvent = MessageSentEvent(message, sender = nodeId, receiver = receiver, id = messageId.getAndIncrement())
            this@DistributedRunner.events.put(messageSentEvent)
            if (failedProcesses[nodeId].get()) {
                return
            }
            val shouldBeSend = Random.nextDouble(0.0, 1.0) <= testCfg
                    .networkReliability
            val numberOfFailedProcesses = failedProcesses.sumBy { if (it.get()) 1 else 0 }
            var processShouldFail = false
            if (numberOfFailedProcesses < testCfg.maxNumberOfFailedNodes) {
                val failProb = (testCfg.maxNumberOfFailedNodes - numberOfFailedProcesses) / testCfg.maxNumberOfFailedNodes * 0.1
                processShouldFail = Random.nextDouble(0.0, 1.0) < failProb
            }
            if (processShouldFail) {
                failedProcesses[nodeId].set(true)
                println("[$nodeId]: Failed")
                this@DistributedRunner.events.put(ProcessFailureEvent(nodeId))
                throw NodeFailureException(nodeId)
            }
            if (!shouldBeSend) {
                return
            }
            val duplicates = Random.nextInt(1, testCfg.duplicationRate + 1)
            for (i in 0 until duplicates) {
                messageQueue.put(messageSentEvent)
            }
        }

        override fun sendLocal(message: Message) {
            //println("[$nodeId]: Send local message $message")
            this@DistributedRunner.events.put(LocalMessageSentEvent(message, nodeId))
        }

        override val events: List<Event>
            get() = if (!isRunning) this@DistributedRunner.events.toList() else emptyList()

        override fun getAddress(cls: Class<out Node<Message>>, i: Int): Int {
            TODO("Not yet implemented")
        }

        override fun getNumberOfNodeType(cls: Class<out Node<Message>>): Int {
            TODO("Not yet implemented")
        }
    }
}
