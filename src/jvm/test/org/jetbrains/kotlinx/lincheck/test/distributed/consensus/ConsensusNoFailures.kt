package org.jetbrains.kotlinx.lincheck.test.distributed.consensus

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.distributed.createDistributedOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import kotlin.random.Random

sealed class Message
data class Offer(val value: Int, val initializer: Int) : Message()
data class Result(val value: Int) : Message()

class ConsensusNoFailures(val env: Environment<Message, Unit>) : Node<Message, Unit> {
    private val offers = Array(env.numberOfNodes) { mutableListOf<Offer>() }
    private val results = Array<Int?>(env.numberOfNodes) { null }
    private var ok: Boolean? = null
    private val semaphore = Signal()
    private val rand = Random(env.nodeId)

    override fun onMessage(message: Message, sender: Int) {
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
                checkConsensusIsCorrect()
            }
        }
    }

    private fun check() {
        // Check if all offers received and we can form the result.
        for (offs in offers) {
            if (offs.size != env.numberOfNodes) continue
            val res = offs.map { it.value }.minOrNull()
            val sender = offs[0].initializer
            if (sender != env.nodeId) {
                env.send(Result(res!!), sender)
            } else {
                results[env.nodeId] = res
                checkConsensusIsCorrect()
            }
            offs.clear()
        }
    }

    private fun checkConsensusIsCorrect() {
        // Check if all results received.
        if (results.none { it == null }) {
            ok = results.all { it == results[0] }
            results.fill(null)
            semaphore.signal()
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun startElection(): Boolean {
        ok = null
        val offer = Offer(nextValue(), initializer = env.nodeId)
        offers[env.nodeId].add(offer)
        env.broadcast(offer)
        semaphore.await()
        return ok!!
    }

    private fun nextValue() = rand.nextInt()
}

class Checker : VerifierState() {
    suspend fun startElection() = true
    override fun extractState(): Any = true
}

class ConsensusNaiveTest {
    @Test
    fun test() = createDistributedOptions<Message>()
        .addNodes<ConsensusNoFailures>(nodes = 3, minNodes = 2)
        .sequentialSpecification(Checker::class.java)
        .actorsPerThread(3)
        .invocationsPerIteration(500_000)
        .iterations(1)
        .check(ConsensusNoFailures::class.java)
}