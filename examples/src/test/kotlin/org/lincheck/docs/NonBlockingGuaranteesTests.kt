package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

class ConcurrentHashMapTest {
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation
    fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking { checkObstructionFreedom = true }
        scenarioGeneration {
            threads = 2
            operationsPerThread = 1
        }
    }, this::class)
}

class ConcurrentHashMapWithBlockingTest {
    private val map = ConcurrentHashMap<Int, Int>()

    @Operation(blocking = true)
    fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking { checkObstructionFreedom = true }
        scenarioGeneration {
            threads = 2
            operationsPerThread = 1
        }
    }, this::class)
}

class ConcurrentSkipListMapTest {
    private val map = ConcurrentSkipListMap<Int, Int>()

    @Operation
    fun put(key: Int, value: Int) = map.put(key, value)

    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking { checkObstructionFreedom = true }
    }, this::class)
}
