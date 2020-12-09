package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification

enum class MessageOrder {
    SYNCHRONOUS,
    FIFO,
    ASYNCHRONOUS
}

class DistributedOptions<Message> : Options<DistributedOptions<Message>,
        DistributedCTestConfiguration<Message>>() {
    var networkReliability: Double = 1.0
    var messageOrder: MessageOrder = MessageOrder.SYNCHRONOUS
    var maxNumberOfFailedNodes: Int = 0
    var supportRecovery: Boolean = true
    var invocationsPerIteration: Int = DistributedCTestConfiguration.DEFAULT_INVOCATIONS
    var maxDelay : Int = 0
    var maxMessageCount : Int = Int.MAX_VALUE
    var maxMessagePerProcess : Int = Int.MAX_VALUE
    var duplicationRate : Int = 1
    var testClasses = HashMap<Class<out Node<Message>>, Int>()
    var useVectorClock = false

    fun delay(delay : Int) : DistributedOptions<Message> {
        this.maxDelay = delay
        return this
    }


    fun networkReliability(networkReliability: Double): DistributedOptions<Message> {
        this.networkReliability = networkReliability
        return this
    }

    fun testClass(cls : Class<out Node<Message>>, numberOfInstances : Int): DistributedOptions<Message> {
        this.testClasses[cls] = numberOfInstances
        return this
    }

    fun messageOrder(messageOrder: MessageOrder): DistributedOptions<Message> {
        this.messageOrder = messageOrder
        return this
    }

    fun maxNumberOfFailedNodes(maxNumOfFailedNodes: Int): DistributedOptions<Message> {
        if (maxNumOfFailedNodes > threads) {
            throw IllegalArgumentException("Maximum number of failed nodes " +
                    "is more than total number of nodes")
        }
        this.maxNumberOfFailedNodes = maxNumOfFailedNodes
        return this
    }

    fun supportRecovery(supportRecovery: Boolean): DistributedOptions<Message> {
        this.supportRecovery = supportRecovery
        return this
    }

    fun invocationsPerIteration(invocations: Int): DistributedOptions<Message> {
        this.invocationsPerIteration = invocations
        return this
    }

    fun maxMessageCount(count : Int) : DistributedOptions<Message> {
        this.maxMessageCount = count
        return this
    }

    fun maxMessageCountPerProcess(count : Int) : DistributedOptions<Message> {
        this.maxMessagePerProcess = count
        return this
    }

    fun duplicationRate(rate : Int) : DistributedOptions<Message> {
        this.duplicationRate = rate
        return this
    }

    fun useVectorClock(useClock : Boolean) : DistributedOptions<Message> {
        this.useVectorClock = useClock
        return this
    }

    override fun createTestConfigurations(testClass: Class<*>): DistributedCTestConfiguration<Message> {
        return DistributedCTestConfiguration(testClass, iterations, threads,
                actorsPerThread, executionGenerator,
                verifier, invocationsPerIteration, networkReliability,
                messageOrder, maxNumberOfFailedNodes, supportRecovery,
                maxDelay, maxMessageCount, maxMessagePerProcess, duplicationRate,
                requireStateEquivalenceImplementationCheck, false,
                chooseSequentialSpecification(sequentialSpecification, testClass), timeoutMs)
    }
}