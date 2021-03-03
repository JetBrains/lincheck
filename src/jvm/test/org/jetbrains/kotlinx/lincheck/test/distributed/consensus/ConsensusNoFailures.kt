package org.jetbrains.kotlinx.lincheck.test.distributed.consensus

import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.stress.LogLevel
import org.jetbrains.kotlinx.lincheck.distributed.stress.logMessage
import org.junit.Test
import java.util.concurrent.ThreadLocalRandom

sealed class Message
data class Offer(val value: Int, val initializer: Int) : Message()
data class Result(val value: Int) : Message()

class ConsensusNoFailures(val env: Environment<Message, Unit>) : Node<Message> {
    private val offers = Array(env.numberOfNodes) {
        mutableListOf<Offer>()
    }

    private val results = Array<Int?>(env.numberOfNodes) {
        null
    }

    private var ok: Boolean? = null

    private val semaphore = Semaphore(1, 1)

    override suspend fun onMessage(message: Message, sender: Int) {
        when (message) {
            is Offer -> {
                val initializer = message.initializer
                if (offers[initializer].isEmpty()) {
                    val myOffer = Offer(nextValue(), initializer)
                    offers[initializer].add(myOffer)
                    env.broadcast(myOffer)
                }
                offers[initializer].add(message)
                check()
            }
            is Result -> {
                results[sender] = message.value
                logMessage(LogLevel.MESSAGES) {
                    "[${env.nodeId}]: Received result $message from $sender"
                }
                checkConsensusIsCorrect()
            }
        }
    }

    private suspend fun check() {
        logMessage(LogLevel.MESSAGES) {
            "[${env.nodeId}]: Check offers ${offers.toList()}"
        }
        // Check if all offers received and we can form the result.
        for (offs in offers) {
            if (offs.size != env.numberOfNodes) continue
            val res = offs.map { it.value }.minOrNull()
            val sender = offs[0].initializer
            logMessage(LogLevel.MESSAGES) {
                "[${env.nodeId}]: Result $res for sender $sender"
            }
            if (sender != env.nodeId) {
                env.send(Result(res!!), sender)
            } else {
                results[env.nodeId] = res
                logMessage(LogLevel.MESSAGES) {
                    "[${env.nodeId}]: Aggregate own result"
                }
                checkConsensusIsCorrect()
            }
            offs.clear()
        }
        // Check if all results received.

    }

    private fun checkConsensusIsCorrect() {
        logMessage(LogLevel.MESSAGES) {
            "[${env.nodeId}]: Check consensus is correct ${results.toList()}"
        }
        if (!results.any { it == null }) {
            ok = results.all { it == results[0] }
            results.fill(null)
            logMessage(LogLevel.MESSAGES) {
                "[${env.nodeId}]: Signal $ok"
            }
            if (semaphore.availablePermits == 1) {
                logMessage(LogLevel.MESSAGES) {
                    "[${env.nodeId}]: Oups, something went wrong"
                }
            }
            semaphore.release()
        }
    }

    @Operation
    suspend fun startElection(): Boolean {
        logMessage(LogLevel.MESSAGES) {
            "[${env.nodeId}]: Start election"
        }
        ok = null
        val offer = Offer(nextValue(), initializer = env.nodeId)
        offers[env.nodeId].add(offer)
        env.broadcast(offer)
        semaphore.acquire()
        logMessage(LogLevel.MESSAGES) {
            "[${env.nodeId}]: Get result"
        }
        return ok!!
    }

    private fun nextValue() = ThreadLocalRandom.current().nextInt()
}

class Checker {
    suspend fun startElection() = true
}

class ConsensusNaiveTest {
    @Test
    fun test() {
        LinChecker.check(
            ConsensusNoFailures::class
                .java, DistributedOptions<Message, Unit>().requireStateEquivalenceImplCheck
                (false).sequentialSpecification(Checker::class.java).threads
                (3).actorsPerThread(1).messageOrder(MessageOrder.FIFO).actorsPerThread(2)
                .invocationsPerIteration(30).iterations(1000)
        )
    }
}