package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.consumeCPU
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random


open class DistributedRunner(val strategy: DistributedStrategy,
                             testClass: Class<*>,
                             validationFunctions: List<Method>?) : Runner(strategy, testClass,
        validationFunctions) {
    private lateinit var testInstances: Array<Process>
    private lateinit var messageCounts: MutableList<AtomicInteger>
    private val messageRequests = Collections.synchronizedCollection(PriorityQueue<MessageRequest>())
    private val localMessages = Array<ConcurrentLinkedQueue<Message>>(scenario.threads) { ConcurrentLinkedQueue() }
    private var time = 0
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val messageHistory : MutableCollection<MessageRequest> = Collections.synchronizedCollection(mutableListOf<MessageRequest>())
    private val failedProcesses = Array(scenario.threads) {false}

    @Volatile
    private var isFinished = false

    private val testThreadExecutions = Array(scenario.threads) { t ->
        TestThreadExecutionGenerator.create(this, t, scenario.parallelExecution[t], null, false, false)
    }

    private val executor = newFixedThreadPool(scenario.threads)

    private inner class MessageHandler : Thread("Message Handler") {
        override fun run() {
            while (!isFinished) {
                var requests: List<MessageRequest> = emptyList()
                var nextDelivery: Int
                var delay = 0
                lock.withLock {
                    println("message handler queue lock")
                    while(messageRequests.isEmpty() && !isFinished) {
                        println("await condition")
                        condition.await()
                    }
                    if (isFinished) {
                        return
                    }
                    println("message handler processing message")

                    nextDelivery = messageRequests.first().timestamp
                    requests = messageRequests.filter { it.timestamp == nextDelivery }
                    messageRequests.removeAll(requests)
                    delay = nextDelivery - time
                    time = nextDelivery
                }
                consumeCPU(delay)
                requests.forEach {
                    println("Message handler -- from: ${it.from} to: ${it.to}" +
                            " ${it
                                    .message}")
                    println("test instances size ${testInstances.size}")
                    testInstances[it.to].onMessage(it
                            .from, it.message)
                }
            }
        }
    }

    private lateinit var messageHandler : MessageHandler


    private val environments: Array<Environment> = Array(scenario.threads) {
        createEnvironment(it, strategy.testCfg)
    }

    init {
        testThreadExecutions.forEach { it.allThreadExecutions = testThreadExecutions }
    }

    private fun reset() {
        messageHistory.clear()
        failedProcesses.fill(false)
        messageHandler = MessageHandler()
        println("number of threads which should be ${scenario.threads}")
        testInstances = Array(scenario.threads) {
            testClass.getConstructor(Environment::class.java).newInstance(environments[it]) as Process
        }
        messageCounts = Collections.synchronizedList(Array(scenario.threads) {
            AtomicInteger(0)
        }.asList())
        messageRequests.clear()
        time = 0
        isFinished = false
        messageHandler.start()
        val useClocks = Random.nextBoolean()
        testThreadExecutions.forEachIndexed { t, ex ->
            ex.testInstance = testInstances[t]
            val threads = scenario.threads
            val actors = scenario.parallelExecution[t].size
            ex.useClocks = useClocks
            ex.curClock = 0
            ex.clocks = Array(actors) { emptyClockArray(threads) }
            ex.results = arrayOfNulls(actors)
        }
    }

    override fun run(): InvocationResult {
        reset()
        testThreadExecutions.map { executor.submit(it) }.forEach { future ->
            try {
                future.get(20, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                isFinished = true
                lock.withLock { condition.signal() }
                messageHandler.join()
                println("Timeout exception")
                val threadDump = Thread.getAllStackTraces().filter { (t, _) -> t is FixedActiveThreadsExecutor.TestThread }
                return DeadlockInvocationResult(threadDump)
            } catch (e: ExecutionException) {
                return UnexpectedExceptionInvocationResult(e.cause!!)
            }
        }
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
        isFinished = true
        lock.withLock { condition.signal() }
        messageHandler.join()
        messageCounts.forEachIndexed { t, c ->
            if (c.get() > strategy.testCfg.messagePerProcess) {
                return InvariantsViolatedInvocationResult(scenario,
                        "Process $t send more messages than expected (actual count is" +
                                " ${c.get()})")
            }
        }
        val totalNumberOfMessages = messageCounts.fold(0) { acc, c ->
            acc + c.get()
        }
        if (totalNumberOfMessages > strategy.testCfg.totalMessageCount) {
            return InvariantsViolatedInvocationResult(scenario,
                    "Total number of messages is more than " +
                            "expected (actual is $totalNumberOfMessages)")
        }
        val results = ExecutionResult(emptyList(), parallelResultsWithClock,
                emptyList())
        return CompletedInvocationResult(results)
    }

    override fun close() {
        super.close()
        executor.shutdown()
    }

    private abstract inner class EnvironmentImpl(override val processId: Int,
                                                 override val nProcesses:
                                                 Int, val testCfg: DistributedCTestConfiguration) : Environment {
        override fun send(destId: Int, message: Message) {
            println("from: $processId to: $destId $message")
            assert(!isFinished)
            val shouldBeSend = Random.nextDouble(0.0, 1.0) <= testCfg
                    .networkReliability
            val numberOfFailedProcesses = failedProcesses.sumBy { if (it) 1 else 0 }
            if (numberOfFailedProcesses < testCfg.maxNumberOfFailedNodes) {
                val failProb = (testCfg.maxNumberOfFailedNodes - numberOfFailedProcesses) / testCfg.maxNumberOfFailedNodes * 0.1
                val processShouldFail = Random.nextDouble(0.0, 1.1) < failProb
            }
            println("from $processId to $destId $message shouldBeSend = $shouldBeSend")
            if (shouldBeSend) {
                lock.withLock {
                    println("$processId queue lock")
                    val request = MessageRequest(deliveryTime(), processId, destId, message)
                    messageRequests.add(request)
                    messageHistory.add(request)
                    condition.signal()
                    messageCounts[processId].incrementAndGet()
                }.also {  println("$processId queue unlock") }
            }
        }

        override fun sendLocal(message: Message) {
            localMessages[processId].add(message)
        }

        abstract fun deliveryTime(): Int
    }

    private inner class SynchronousEnvironmentImp(processId: Int,
                                                  nProcesses:
                                                  Int, testCfg:
                                                  DistributedCTestConfiguration) : EnvironmentImpl(processId, nProcesses, testCfg) {
        override fun deliveryTime(): Int {

            val minTime = if (messageRequests.isEmpty()) {
                time
            } else {
                messageRequests.last().timestamp
            }
            return Random.nextInt(minTime, time + testCfg.maxDelay + 1)
        }
    }

    private inner class AsynchronousEnvironmentImp(processId: Int,
                                                   nProcesses:
                                                   Int, testCfg:
                                                   DistributedCTestConfiguration) : EnvironmentImpl(processId, nProcesses, testCfg) {
        override fun deliveryTime(): Int {
            return Random.nextInt(time, time + testCfg.maxDelay + 1)
        }
    }

    private inner class FifoEnvironmentImp(processId: Int,
                                           nProcesses:
                                           Int, testCfg:
                                           DistributedCTestConfiguration) : EnvironmentImpl(processId, nProcesses, testCfg) {
        override fun deliveryTime(): Int {
            val minTime = messageRequests.filter { it.from == processId }
                    .min()?.timestamp ?: time
            return Random.nextInt(minTime, time + testCfg.maxDelay + 1)
        }
    }

    private fun createEnvironment(processId: Int, testCfg:
    DistributedCTestConfiguration): Environment {
        return when (testCfg.messageOrder) {
            MessageOrder.SYNCHRONOUS -> SynchronousEnvironmentImp(processId, testCfg.threads, testCfg)
            MessageOrder.FIFO -> FifoEnvironmentImp(processId, testCfg.threads, testCfg)
            MessageOrder.ASYNCHRONOUS -> AsynchronousEnvironmentImp(processId, testCfg.threads, testCfg)
        }
    }
}

sealed class ProcessEvent
class MessageRequest(val timestamp: Int, val from: Int, val to: Int, val
message: Message) :
        ProcessEvent(), Comparable<MessageRequest> {
    override fun compareTo(other: MessageRequest): Int {
        return timestamp - other.timestamp
    }

}
