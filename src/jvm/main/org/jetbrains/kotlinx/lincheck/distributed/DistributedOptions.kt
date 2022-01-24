package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification
import org.jetbrains.kotlinx.lincheck.distributed.CrashMode.*
import org.jetbrains.kotlinx.lincheck.distributed.NetworkPartitionMode.*
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure

/**
 * Represents the guarantees on message order.
 * [MessageOrder.FIFO] guarantees that if message m1 from node A to node B
 * was sent before message m2 from node A to node B, m1 will be received by B before m2.
 * [MessageOrder.ASYNCHRONOUS] gives no guarantees on the order in which messages will be received.
 */
enum class MessageOrder {
    FIFO,
    ASYNCHRONOUS
}

/**
 * The crash returns the node to the initial state, but does not affect the database.
 * [NO_CRASHES] means that there is no such crashes in the system for this node type.
 * Note that the network partitions and other network failure for the node type are still possible.
 * [NO_RECOVER] means that if the node crashed it doesn't recover.
 * [ALL_NODES_RECOVER] means that if the node has crashed, it will be recovered.
 * [MIXED] means that the node may recover or may not recover.
 */
enum class CrashMode {
    NO_CRASHES,
    NO_RECOVER,
    ALL_NODES_RECOVER,
    MIXED
}

/**
 * Network partition mode.
 * [NONE] means no partitions happen in the system, although message still can be lost.
 * [COMPONENTS] means that network partition separates a set of nodes from other nodes.
 * Nodes within the set are still available for each other, as well as nodes outside the set.
 * [SINGLE_EDGE] means that two nodes become unavailable to each other.
 */
enum class NetworkPartitionMode {
    NONE,
    COMPONENTS,
    SINGLE_EDGE
}

/**
 * Information about the node type (Class<out Node>).
 * [minNumberOfInstances] is the minimum number of instances of this node type to make the system work.
 * [numberOfInstances] is the number of instance for this node type which will be created.
 * [crashType] is the crash type for this node.
 * [networkPartition] is the network partition type for this node.
 * [maxNumberOfCrashesFunction] takes number of nodes and returns maximum number of unavailable nodes.
 * [maxNumberOfCrashes] is maximum number of crashes for this number of instances.
 */
data class NodeTypeInfo(
    val minNumberOfInstances: Int,
    val numberOfInstances: Int,
    val crashType: CrashMode,
    val networkPartition: NetworkPartitionMode,
    val maxNumberOfCrashesFunction: (Int) -> Int
) {
    fun minimize() =
        NodeTypeInfo(
            minNumberOfInstances,
            numberOfInstances - 1,
            crashType,
            networkPartition,
            maxNumberOfCrashesFunction
        )

    val maxNumberOfCrashes = if (crashType == NO_CRASHES && networkPartition == NONE) {
        0
    } else {
        maxNumberOfCrashesFunction(numberOfInstances)
    }
}

/**
 * Options for distributed algorithms.
 */
class DistributedOptions<Message, DB> internal constructor(private val databaseFactory: () -> DB) :
    Options<DistributedOptions<Message, DB>,
            DistributedCTestConfiguration<Message, DB>>() {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5000
    }

    private var messageLoss: Boolean = true
    private var messageOrder: MessageOrder = MessageOrder.FIFO
    private var invocationsPerIteration: Int = DistributedCTestConfiguration.DEFAULT_INVOCATIONS
    private var messageDuplication: Boolean = false
    private var testClasses = mutableMapOf<Class<out Node<Message, DB>>, NodeTypeInfo>()
    private var logFileName: String? = null
    private var crashNotifications = true
    private var _testClass: Class<out Node<Message, DB>>? = null

    init {
        timeoutMs = DEFAULT_TIMEOUT_MS
    }

    /**
     * Sets if messages can be lost.
     */
    fun messageLoss(messageLoss: Boolean): DistributedOptions<Message, DB> {
        this.messageLoss = messageLoss
        return this
    }

    /**
     * Adds node type and information about it.
     */
    fun nodeType(
        cls: Class<out Node<Message, DB>>,
        numberOfInstances: Int,
        crashType: CrashMode = NO_CRASHES,
        networkPartition: NetworkPartitionMode = NONE,
        maxNumberOfCrashedNodes: (Int) -> Int = { it }
    ): DistributedOptions<Message, DB> {
        this.testClasses[cls] =
            NodeTypeInfo(1, numberOfInstances, crashType, networkPartition, maxNumberOfCrashedNodes)
        return this
    }

    fun nodeType(
        cls: Class<out Node<Message, DB>>,
        minNumberOfInstances: Int,
        numberOfInstances: Int,
        crashType: CrashMode = NO_CRASHES,
        networkPartition: NetworkPartitionMode = NONE,
        maxNumberOfCrashedNodes: (Int) -> Int = { it }
    ): DistributedOptions<Message, DB> {
        this.testClasses[cls] = NodeTypeInfo(
            minNumberOfInstances,
            numberOfInstances,
            crashType,
            networkPartition,
            maxNumberOfCrashedNodes
        )
        return this
    }

    /**
     * Sets the message order for the system. See [MessageOrder]
     */
    fun messageOrder(messageOrder: MessageOrder): DistributedOptions<Message, DB> {
        this.messageOrder = messageOrder
        return this
    }

    fun invocationsPerIteration(invocations: Int): DistributedOptions<Message, DB> {
        this.invocationsPerIteration = invocations
        return this
    }

    /**
     * Sets if messages may be duplicated.
     */
    fun messageDuplications(duplications: Boolean): DistributedOptions<Message, DB> {
        this.messageDuplication = duplications
        return this
    }

    /**
     * Sets the filename where the logs will be stored in case of failure. See [org.jetbrains.kotlinx.lincheck.distributed.event.Event]
     */
    fun storeLogsForFailedScenario(fileName: String): DistributedOptions<Message, DB> {
        this.logFileName = fileName
        return this
    }

    /**
     * Sets if [Node.onNodeUnavailable] should be called after crash or partition.
     */
    fun sendCrashNotifications(crashNotifications: Boolean): DistributedOptions<Message, DB> {
        this.crashNotifications = crashNotifications
        return this
    }

    override fun createTestConfigurations(testClass: Class<*>): DistributedCTestConfiguration<Message, DB> =
        DistributedCTestConfiguration(
            testClass, iterations, threads,
            actorsPerThread, executionGenerator,
            verifier, invocationsPerIteration, messageLoss,
            messageOrder, messageDuplication, testClasses, logFileName, crashNotifications, databaseFactory,
            requireStateEquivalenceImplementationCheck, minimizeFailedScenario,
            chooseSequentialSpecification(sequentialSpecification, testClass), timeoutMs, customScenarios
        )

    /**
     * The same as [createTestConfigurations] but the test class is not specified.
     */
    fun createTestConfiguration() = DistributedCTestConfiguration(
        testClass, iterations, threads,
        actorsPerThread, executionGenerator,
        verifier, invocationsPerIteration, messageLoss,
        messageOrder, messageDuplication, testClasses, logFileName, crashNotifications, databaseFactory,
        requireStateEquivalenceImplementationCheck, minimizeFailedScenario,
        chooseSequentialSpecification(sequentialSpecification, testClass), timeoutMs, customScenarios
    )

    /**
     * Extracts test class from node types.
     */
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

    /**
     * Runs the tests for current configuration.
     */
    fun check() {
        threads = testClasses[testClass]!!.numberOfInstances
        LinChecker.check(testClass, options = this)
    }

    /**
     * Runs the tests and returns failure.
     */
    internal fun checkImpl(): LincheckFailure? {
        threads = testClasses[testClass]!!.numberOfInstances
        return LinChecker(testClass, this).checkImpl()
    }
}

fun <Message> createDistributedOptions() = DistributedOptions<Message, Unit> { }
fun <Message, DB> createDistributedOptions(factory: () -> DB) = DistributedOptions<Message, DB>(factory)