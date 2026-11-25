package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test
import java.util.LinkedList
import java.util.concurrent.ConcurrentLinkedQueue

class ConcurrentLinkedQueueTest {
    private val s = ConcurrentLinkedQueue<Int>()

    @Operation
    fun add(value: Int) = s.add(value)

    @Operation
    fun poll(): Int? = s.poll()

    @Test
    fun stressTest() = Lincheck.runConcurrentTest(LincheckSettings {
        stress()
        scenarioGeneration {
            sequentialSpecification = SequentialQueue::class.java
        }
    }, this::class)
}

class SequentialQueue {
    private val s = LinkedList<Int>()

    fun add(x: Int) = s.add(x)
    fun poll(): Int? = s.poll()
}
