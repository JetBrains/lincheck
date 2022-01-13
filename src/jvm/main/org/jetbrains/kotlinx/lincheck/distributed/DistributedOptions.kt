package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification
import java.lang.IllegalArgumentException
import java.util.*

enum class MessageOrder {
    FIFO,
    ASYNCHRONOUS
}

enum class CrashMode {
    NO_CRASHES,
    NO_RECOVER,
    ALL_NODES_RECOVER,
    MIXED
}

enum class NetworkPartitionMode {
    NONE,
    HALVES,
    SINGLE
}

data class NodeTypeInfo(
    val minNumberOfInstances: Int,
    val maxNumberOfInstances: Int,
    val crashType: CrashMode,
    val networkPartition: NetworkPartitionMode,
    val maxNumberOfCrashes: (Int) -> Int
) {
    fun minimize() =
        NodeTypeInfo(minNumberOfInstances, maxNumberOfInstances - 1, crashType, networkPartition, maxNumberOfCrashes)

    val maxCrashes = if (crashType == CrashMode.NO_CRASHES && networkPartition == NetworkPartitionMode.NONE) {
        0
    } else {
        maxNumberOfCrashes(maxNumberOfInstances)
    }
}

class DistributedOptions<Message, DB> internal constructor(private val databaseFactory: () -> DB) :
    Options<DistributedOptions<Message, DB>,
            DistributedCTestConfiguration<Message, DB>>() {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5000
    }

    private var isNetworkReliable: Boolean = true
    private var messageOrder: MessageOrder = MessageOrder.FIFO
    private var invocationsPerIteration: Int = DistributedCTestConfiguration.DEFAULT_INVOCATIONS
    private var messageDuplication: Boolean = false
    private var testClasses = HashMap<Class<out Node<Message, DB>>, NodeTypeInfo>()
    private var logFileName: String? = null
    private var _testClass: Class<out Node<Message, DB>>? = null

    init {
        timeoutMs = DEFAULT_TIMEOUT_MS
    }

    fun networkReliable(isReliable: Boolean): DistributedOptions<Message, DB> {
        this.isNetworkReliable = isReliable
        return this
    }

    fun nodeType(
        cls: Class<out Node<Message, DB>>,
        numberOfInstances: Int,
        crashType: CrashMode = CrashMode.NO_CRASHES,
        networkPartition: NetworkPartitionMode = NetworkPartitionMode.NONE,
        maxNumberOfCrashedNodes: (Int) -> Int = { it }
    ): DistributedOptions<Message, DB> {
        this.testClasses[cls] =
            NodeTypeInfo(numberOfInstances, numberOfInstances, crashType, networkPartition, maxNumberOfCrashedNodes)
        return this
    }

    fun nodeType(
        cls: Class<out Node<Message, DB>>,
        minNumberOfInstances: Int,
        maxNumberOfInstances: Int,
        crashType: CrashMode = CrashMode.NO_CRASHES,
        networkPartition: NetworkPartitionMode = NetworkPartitionMode.NONE,
        maxNumberOfCrashedNodes: (Int) -> Int = { it }
    ): DistributedOptions<Message, DB> {
        this.testClasses[cls] = NodeTypeInfo(
            minNumberOfInstances,
            maxNumberOfInstances,
            crashType,
            networkPartition,
            maxNumberOfCrashedNodes
        )
        return this
    }

    fun messageOrder(messageOrder: MessageOrder): DistributedOptions<Message, DB> {
        this.messageOrder = messageOrder
        return this
    }

    fun invocationsPerIteration(invocations: Int): DistributedOptions<Message, DB> {
        this.invocationsPerIteration = invocations
        return this
    }

    fun messageDuplications(duplications: Boolean): DistributedOptions<Message, DB> {
        this.messageDuplication = duplications
        return this
    }

    fun storeLogsForFailedScenario(fileName: String): DistributedOptions<Message, DB> {
        logFileName = fileName
        return this
    }

    override fun createTestConfigurations(testClass: Class<*>): DistributedCTestConfiguration<Message, DB> =
        DistributedCTestConfiguration(
            testClass, iterations, threads,
            actorsPerThread, executionGenerator,
            verifier, invocationsPerIteration, isNetworkReliable,
            messageOrder, messageDuplication, testClasses, logFileName, databaseFactory,
            requireStateEquivalenceImplementationCheck, minimizeFailedScenario,
            chooseSequentialSpecification(sequentialSpecification, testClass), timeoutMs, customScenarios
        )

    fun createTestConfiguration() = DistributedCTestConfiguration(
        testClass, iterations, threads,
        actorsPerThread, executionGenerator,
        verifier, invocationsPerIteration, isNetworkReliable,
        messageOrder, messageDuplication, testClasses, logFileName, databaseFactory,
        requireStateEquivalenceImplementationCheck, minimizeFailedScenario,
        chooseSequentialSpecification(sequentialSpecification, testClass), timeoutMs, customScenarios
    )

    private val testClass: Class<out Node<Message, DB>>
        get() {
            if (_testClass == null) {
                _testClass = testClasses.keys.find {
                    it.methods.any { m ->
                        m.isAnnotationPresent(Operation::class.java)
                    }
                } ?: throw IllegalArgumentException("No operations to check")
            }
            return _testClass!!
        }

    fun check() {
        threads = testClasses[testClass]!!.maxNumberOfInstances
        LinChecker.check(testClass, options = this)
    }
}

fun <Message> createDistributedOptions() = DistributedOptions<Message, Unit> { }
fun <Message, DB> createDistributedOptions(factory: () -> DB) = DistributedOptions<Message, DB>(factory)