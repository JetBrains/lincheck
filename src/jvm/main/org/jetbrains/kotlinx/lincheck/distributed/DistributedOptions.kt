package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification
import java.util.*

enum class MessageOrder {
    SYNCHRONOUS,
    FIFO,
    ASYNCHRONOUS
}


enum class RecoveryMode {
    NO_RECOVERIES,
    ALL_NODES_RECOVER,
    MIXED
}

class DistributedOptions<Message, Log> : Options<DistributedOptions<Message, Log>,
        DistributedCTestConfiguration<Message, Log>>() {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 10000
    }

    private var isNetworkReliable: Boolean = true
    private var messageOrder: MessageOrder = MessageOrder.SYNCHRONOUS
    private var maxNumberOfFailedNodes: (Int) -> Int = { 0 }
    private var supportRecovery: RecoveryMode = RecoveryMode.NO_RECOVERIES
    private var invocationsPerIteration: Int = DistributedCTestConfiguration.DEFAULT_INVOCATIONS
    private var messageDuplication: Boolean = false
    private var networkPartitions: Boolean = false
    private var testClasses = HashMap<Class<out Node<Message>>, Pair<Int, Boolean>>()
    private var useVectorClock = false
    private var asyncRun = false

    init {
        timeoutMs = DEFAULT_TIMEOUT_MS
    }

    fun networkReliable(isReliable: Boolean): DistributedOptions<Message, Log> {
        this.isNetworkReliable = isReliable
        return this
    }

    fun nodeType(
        cls: Class<out Node<Message>>,
        numberOfInstances: Int,
        canFail: Boolean = true
    ): DistributedOptions<Message, Log> {
        this.testClasses[cls] = numberOfInstances to canFail
        return this
    }

    fun messageOrder(messageOrder: MessageOrder): DistributedOptions<Message, Log> {
        this.messageOrder = messageOrder
        return this
    }

    fun setMaxNumberOfFailedNodes(maxNumOfFailedNodes: (Int) -> Int): DistributedOptions<Message, Log> {
        this.maxNumberOfFailedNodes = maxNumOfFailedNodes
        return this
    }

    fun supportRecovery(supportRecovery: RecoveryMode): DistributedOptions<Message, Log> {
        this.supportRecovery = supportRecovery
        return this
    }

    fun invocationsPerIteration(invocations: Int): DistributedOptions<Message, Log> {
        this.invocationsPerIteration = invocations
        return this
    }

    fun messageDuplications(duplications: Boolean): DistributedOptions<Message, Log> {
        this.messageDuplication = duplications
        return this
    }

    fun runOperationsAsynchronously(asyncRun: Boolean): DistributedOptions<Message, Log> {
        this.asyncRun = asyncRun
        return this
    }

    fun networkPartitions(partitions: Boolean): DistributedOptions<Message, Log> {
        this.networkPartitions = partitions
        return this
    }

    fun useVectorClock(useClock: Boolean): DistributedOptions<Message, Log> {
        this.useVectorClock = useClock
        return this
    }

    override fun createTestConfigurations(testClass: Class<*>): DistributedCTestConfiguration<Message, Log> {
        return DistributedCTestConfiguration(
            testClass, iterations, threads,
            actorsPerThread, executionGenerator,
            verifier, invocationsPerIteration, isNetworkReliable,
            messageOrder, maxNumberOfFailedNodes, supportRecovery,
            messageDuplication, networkPartitions, asyncRun, testClasses,
            requireStateEquivalenceImplementationCheck, false,
            chooseSequentialSpecification(sequentialSpecification, testClass), timeoutMs
        )
    }
}