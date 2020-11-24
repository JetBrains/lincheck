package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.getMethod
import org.jetbrains.kotlinx.lincheck.runner.*
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

internal interface MessageQueue {
    fun put(msg: Message)
    fun get(): Message?
    fun clear()
}

internal class SynchronousMessageQueue : MessageQueue {
    private val messageQueue = LinkedBlockingQueue<Message>()
    override fun put(msg: Message) {
        messageQueue.add(msg)
    }

    override fun get(): Message? {
        return messageQueue.poll()
    }

    override fun clear() {
        messageQueue.clear()
    }
}

class FifoMessageQueue(numberOfNodes: Int) : MessageQueue {
    private val messageQueues = Array(numberOfNodes) {
        LinkedBlockingQueue<Message>()
    }

    override fun put(msg: Message) {
        messageQueues[msg.receiver].add(msg)
    }

    override fun get(): Message? {
        if (messageQueues.none { it.isNotEmpty() }) {
            return null
        }
        return messageQueues.filter { it.isNotEmpty() }.shuffled()[0].poll()
    }

    override fun clear() {
        messageQueues.forEach { it.clear() }
    }
}

class AsynchronousMessageQueue(private val numberOfNodes: Int) : MessageQueue {
    private val messageQueues = Array(numberOfNodes) {
        LinkedBlockingQueue<Message>()
    }

    override fun put(msg: Message) {
        val queueToPut = Random.nextInt(numberOfNodes)
        messageQueues[queueToPut].add(msg)
    }

    override fun get(): Message? {
        if (messageQueues.none { it.isNotEmpty() }) {
            return null
        }
        return messageQueues.filter { it.isNotEmpty() }.shuffled()[0].poll()
    }

    override fun clear() {
        messageQueues.forEach { it.clear() }
    }
}


open class DistributedRunner(strategy: DistributedStrategy,
                             val testCfg: DistributedCTestConfiguration,
                             testClass: Class<*>,
                             validationFunctions: List<Method>,
                             stateRepresentationFunction: Method?) : Runner(
        strategy, testClass,
        validationFunctions,
        stateRepresentationFunction) {
    private val messageQueue: MessageQueue = when (testCfg.messageOrder) {
        MessageOrder.SYNCHRONOUS -> SynchronousMessageQueue()
        MessageOrder.FIFO -> FifoMessageQueue(testCfg.threads)
        MessageOrder.ASYNCHRONOUS -> AsynchronousMessageQueue(testCfg.threads)
    }
    private lateinit var testInstances: Array<Node>
    private lateinit var messageCounts: MutableList<AtomicInteger>
    private lateinit var messageBroker: MessageBroker
    private val messageHistory = ConcurrentLinkedQueue<Event>()
    private val failedProcesses = Array(testCfg.threads) { false }

    private inner class MessageBroker : Thread() {
        override fun run() {
            while (isRunning) {
                val message = messageQueue.get() ?: continue
                messageHistory.add(MessageDeliveredEvent(message))
                testInstances[message.receiver].onMessage(message)
            }
        }
    }

    @Volatile
    private var isRunning = false


    private lateinit var testThreadExecutions: Array<TestThreadExecution>

    private val executor = newFixedThreadPool(testCfg.threads)


    private val environments: Array<Environment> = Array(testCfg.threads) {
        EnvironmentImpl(it, testCfg.threads)
    }

    override fun initialize() {
        super.initialize()
        check(scenario.parallelExecution.size == testCfg.threads) {
            "Parrallel execution size is ${scenario.parallelExecution.size}"
        }
        testThreadExecutions = Array(testCfg.threads) { t ->
            TestThreadExecutionGenerator.create(this, t, scenario.parallelExecution[t], null, false)
        }
        testThreadExecutions.forEach { it.allThreadExecutions = testThreadExecutions }
        messageBroker = MessageBroker()
    }

    private fun reset() {
        messageHistory.clear()
        messageQueue.clear()
        failedProcesses.fill(false)
        testInstances = Array(testCfg.threads) {
            testClass.getConstructor(Environment::class.java).newInstance(environments[it]) as Node
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
        var res = "\nMESSAGES\n"
        for (event in messageHistory) {
            res += event.toString() + '\n'
        }
        res += "NODE STATES\n"
        for (testInstance in testInstances) {
            res += stateRepresentationFunction?.let { getMethod(testInstance, it) }?.invoke(testInstance) as String? + '\n'
        }
        return res
    }

    override fun run(): InvocationResult {
        reset()
        testThreadExecutions.map { executor.submit(it) }.forEach { future ->
            try {
                future.get(20, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                println("Timeout exception")
                isRunning = false
                messageBroker.join()
                val threadDump = Thread.getAllStackTraces().filter { (t, _) -> t is FixedActiveThreadsExecutor.TestThread }
                return DeadlockInvocationResult(threadDump)
            } catch (e: ExecutionException) {
                return UnexpectedExceptionInvocationResult(e.cause!!)
            }
        }
        isRunning = false
        val parallelResultsWithClock = testThreadExecutions.map { ex ->
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
                return ValidationFailureInvocationResult(s, functionName, exception)
            }
        }
        messageBroker.join()
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

    private inner class EnvironmentImpl(override val processId: Int,
                                        override val nProcesses:
                                        Int) : Environment {
        override fun send(message: Message) {
            if (failedProcesses[processId]) {
                return
            }
            //assert(!isFinished)
            val shouldBeSend = Random.nextDouble(0.0, 1.0) <= testCfg
                    .networkReliability
            val numberOfFailedProcesses = failedProcesses.sumBy { if (it) 1 else 0 }
            var processShouldFail = false
            if (numberOfFailedProcesses < testCfg.maxNumberOfFailedNodes) {
                val failProb = (testCfg.maxNumberOfFailedNodes - numberOfFailedProcesses) / testCfg.maxNumberOfFailedNodes * 0.1
                processShouldFail = Random.nextDouble(0.0, 1.0) < failProb
            }
            if (processShouldFail) {
                failedProcesses[processId] = true
            }
            if (!shouldBeSend) {
                return
            }
            val duplicates = Random.nextInt(1, testCfg.duplicationRate + 1)
            for (i in 0 until duplicates) {
                messageHistory.add(MessageSentEvent(message))
                messageQueue.put(message)
            }
        }
    }
}

sealed class Event

class MessageSentEvent(val message: Message) : Event() {
    override fun toString(): String {
        return "Process ${message.sender} send message to process ${message.receiver}, body: ${message.body}, headers: ${message.headers}"
    }
}

class MessageDeliveredEvent(val message: Message) : Event() {
    override fun toString(): String {
        return "Process ${message.receiver} received message from process ${message.sender}, body: ${message.body}, headers: ${message.headers}"
    }
}
