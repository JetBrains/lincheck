package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.verifier.SerializabilityVerifier
import kotlin.test.Test

class ConcurrentQueue {
    private val elements: MutableList<Int> = ArrayList()

    fun put(x: Int) = synchronized(this) {
        elements += x
    }

    fun poll(): Int? = synchronized(this) {
        if (elements.isEmpty()) return null
        elements.shuffle()
        elements.removeAt(0)
    }
}

class CorrectSequentialQueue {
    private val elements: MutableList<Int> = ArrayList()

    fun put(x: Int) {
        elements += x
    }

    fun poll(): Int? = if (elements.isEmpty()) null else elements.removeAt(0)
}

@Param(name = "value", gen = IntGen::class, conf = "1:2")
class ConcurrentQueueTest {
    private val q = ConcurrentQueue()

    @Operation
    fun put(@Param(name = "value") x: Int) = q.put(x)

    @Operation
    fun poll(): Int? = q.poll()

    @Test
    fun customVerifierTest() = ModelCheckingOptions()
        .verifier(SerializabilityVerifier::class.java)
        .check(this::class.java)

    @Test
    fun serializabilityTest() = ModelCheckingOptions()
        .actorsBefore(0)
        .actorsAfter(0)
        .actorsPerThread(2)
        .threads(2)
        // Use the `SerializabilityVerifier`
        .verifier(SerializabilityVerifier::class.java)
        // Specify the sequential version of the structure
        .sequentialSpecification(CorrectSequentialQueue::class.java)
        .check(this::class.java)

    @Test
    fun linearizabilityTest() = ModelCheckingOptions()
        .actorsBefore(0)
        .actorsAfter(0)
        .actorsPerThread(2)
        .threads(2)
        // Show the full failed scenario
        .minimizeFailedScenario(false)
        // Specify the sequential version of the structure
        .sequentialSpecification(CorrectSequentialQueue::class.java)
        .check(this::class.java)
}