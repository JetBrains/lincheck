package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification

enum class MessageOrder {
    SYNCHRONOUS,
    FIFO,
    ASYNCHRONOUS
}

class DistributedOptions<Message, Log> : Options<DistributedOptions<Message, Log>,
        DistributedCTestConfiguration<Message, Log>>() {
    var isNetworkReliable: Boolean = true
    var messageOrder: MessageOrder = MessageOrder.SYNCHRONOUS
    var maxNumberOfFailedNodes: (Int) -> Int = { 0 }
    var supportRecovery: Boolean = true
    var invocationsPerIteration: Int = DistributedCTestConfiguration.DEFAULT_INVOCATIONS
    var messageDuplication: Boolean = false
    var networkPartitions: Boolean = false
    var testClasses = HashMap<Class<out Node<Message>>, Int>()
    var useVectorClock = false

    fun networkReliable(isReliable: Boolean): DistributedOptions<Message, Log> {
        this.isNetworkReliable = isReliable
        return this
    }

    fun nodeType(cls: Class<out Node<Message>>, minNumberOfInstances: Int): DistributedOptions<Message, Log> {
        this.testClasses[cls] = minNumberOfInstances
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

    fun supportRecovery(supportRecovery: Boolean): DistributedOptions<Message, Log> {
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
            messageDuplication, networkPartitions, testClasses,
            requireStateEquivalenceImplementationCheck, false,
            chooseSequentialSpecification(sequentialSpecification, testClass), timeoutMs
        )
    }
}