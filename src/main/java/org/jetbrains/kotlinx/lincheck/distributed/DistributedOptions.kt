package org.jetbrains.kotlinx.lincheck.distributed

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification

enum class MessageOrder {
    SYNCHRONOUS,
    FIFO,
    ASYNCHRONOUS
}

class DistributedOptions : Options<DistributedOptions,
        DistributedCTestConfiguration>() {
    var networkReliability: Double = 1.0
    var messageOrder: MessageOrder = MessageOrder.SYNCHRONOUS
    var maxNumberOfFailedNodes: Int = 0
    var supportRecovery: Boolean = true
    var invocationsPerIteration: Int = DistributedCTestConfiguration.DEFAULT_INVOCATIONS
    var maxDelay : Int = 0
    var maxMessageCount : Int = Int.MAX_VALUE
    var maxMessagePerProcess : Int = Int.MAX_VALUE
    var duplicationRate : Int = 1

    fun delay(delay : Int) : DistributedOptions {
        this.maxDelay = delay
        return this
    }


    fun networkReliability(networkReliability: Double): DistributedOptions {
        this.networkReliability = networkReliability
        return this
    }

    fun messageOrder(messageOrder: MessageOrder): DistributedOptions {
        this.messageOrder = messageOrder
        return this
    }

    fun maxNumberOfFailedNodes(maxNumOfFailedNodes: Int): DistributedOptions {
        if (maxNumOfFailedNodes > threads) {
            throw IllegalArgumentException("Maximum number of failed nodes " +
                    "is more than total number of nodes")
        }
        this.maxNumberOfFailedNodes = maxNumOfFailedNodes
        return this
    }

    fun supportRecovery(supportRecovery: Boolean): DistributedOptions {
        this.supportRecovery = true
        return this
    }

    fun invocationsPerIteration(invocations: Int): DistributedOptions {
        this.invocationsPerIteration = invocations
        return this
    }

    fun maxMessageCount(count : Int) : DistributedOptions {
        this.maxMessageCount = count
        return this
    }

    fun maxMessageCountPerProcess(count : Int) : DistributedOptions {
        this.maxMessagePerProcess = count
        return this
    }

    override fun createTestConfigurations(testClass: Class<*>?): DistributedCTestConfiguration {
        return DistributedCTestConfiguration(testClass, iterations, threads,
                actorsPerThread, executionGenerator,
                verifier, invocationsPerIteration, networkReliability,
                messageOrder, maxNumberOfFailedNodes, supportRecovery,
                maxDelay, maxMessageCount, maxMessagePerProcess, duplicationRate,
                requireStateEquivalenceImplementationCheck, minimizeFailedScenario,
                chooseSequentialSpecification(sequentialSpecification, testClass!!), timeoutMs)

    }
}