package org.jetbrains.kotlinx.lincheck.test.distributed.examples.naiveconsensus

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.distributed.createDistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.distributed.event.OperationStartEvent
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test
import kotlin.random.Random

/**
 * Unique offer identifier.
 * [initializer] node which initialized the election
 * [internalId] id of election inside [initializer node][initializer]
 */
data class OfferId(val initializer: Int, val internalId: Int)

data class Offer(val value: Int, val initializer: Int, val internalId: Int) {
    val identifier = OfferId(initializer, internalId)
}

/**
 * Consensus in the absence of failures.
 */
class NaiveConsensus(private val env: Environment<Offer, MutableMap<OfferId, Int>>) :
    Node<Offer, MutableMap<OfferId, Int>> {
    private val offers = mutableMapOf<OfferId, MutableList<Offer>>()
    private val rand = Random(env.nodeId)
    private var id = 0

    override fun onMessage(message: Offer, sender: Int) {
        val offerId = message.identifier
        if (!offers.containsKey(offerId)) {
            val myOffer = Offer(nextValue(), message.initializer, message.internalId)
            offers[offerId] = mutableListOf<Offer>().apply { add(myOffer) }
            env.broadcast(myOffer)
        }
        offers[offerId]!!.add(message)
        appendResults()
    }

    private fun appendResults() =
        offers.filter { it.value.size == env.numberOfNodes }
            .forEach {
                env.database[it.key] = it.value.minOf { o -> o.value }
            }

    override fun validate(events: List<Event>, databases: List<MutableMap<OfferId, Int>>) {
        // All results are the same
        assert(databases.all { it == databases[0] })
        // All elections finished
        val offers = events.filterIsInstance<OperationStartEvent>().groupBy { it.iNode }.flatMap {
            it.value.mapIndexed { index, _ -> OfferId(initializer = it.key, internalId = index) }
        }
        offers.forEach {
            assert(databases[0].containsKey(it))
        }
    }

    /**
     * Starts new election. The values are generated randomly. The results are stored in the database.
     */
    @Operation
    fun startElection() {
        val offer = Offer(nextValue(), initializer = env.nodeId, internalId = id++)
        offers[offer.identifier] = mutableListOf<Offer>().apply { add(offer) }
        env.broadcast(offer)
    }

    private fun nextValue() = rand.nextInt()
}

class NaiveConsensusTest {
    @Test
    fun test() = createDistributedOptions<Offer, MutableMap<OfferId, Int>> { mutableMapOf() }
        .addNodes<NaiveConsensus>(nodes = 3, minNodes = 2)
        .verifier(EpsilonVerifier::class.java)
        .actorsPerThread(3)
        .invocationsPerIteration(500_000)
        .iterations(1)
        .check(NaiveConsensus::class.java)
}