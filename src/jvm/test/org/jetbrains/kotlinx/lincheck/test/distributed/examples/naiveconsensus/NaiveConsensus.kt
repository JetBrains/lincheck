package org.jetbrains.kotlinx.lincheck.test.distributed.examples.naiveconsensus

import org.jetbrains.kotlinx.lincheck.ValueResult
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.event.Event
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.junit.Test

/**
 * Consensus in the absence of failures.
 */
class NaiveConsensus(private val env: NodeEnvironment<Int>) :
    Node<Int> {
    private val proposals = mutableMapOf<Int, Int>()
    var proposeValue: Int? = null
    var consensus: Int? = null
    private val signal = Signal()

    override fun onMessage(message: Int, sender: Int) {
        if (proposeValue == null) {
            propose(message)
        }
        proposals[sender] = message
        if (proposals.size == env.nodes) {
            consensus = proposals.values.minOrNull()!!
            signal.signal()
        }
    }

    @Operation
    suspend fun consensus(value: Int): Int {
        if (consensus != null) return consensus!!
        if (proposeValue == null) {
            propose(value)
        }
        signal.await()
        return consensus!!
    }

    private fun propose(value: Int) {
        proposeValue = value
        proposals[env.id] = value
        env.broadcast(value)
    }
}

class ConsensusVerifier : DistributedVerifier {
    override fun verifyResultsAndStates(
        nodes: Array<out Node<*>>,
        scenario: ExecutionScenario,
        results: ExecutionResult,
        events: List<Event>
    ): Boolean {
        val values = results.parallelResults.flatten()
            .map { it as ValueResult }.map { it.value as Int }
        if (values.any { it != values[0] }) {
            return false
        }
        return scenario.parallelExecution.flatten()
            .map { it.arguments[0] as Int }.contains(values[0])
    }
}

class NaiveConsensusTest {
    @Test
    fun test() = DistributedOptions<Int>()
        .addNodes<NaiveConsensus>(nodes = 3, minNodes = 2)
        .verifier(ConsensusVerifier::class.java)
        .actorsPerThread(3)
        .invocationsPerIteration(150_000)
        .iterations(5)
        .check(NaiveConsensus::class.java)
}