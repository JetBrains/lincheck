package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test

class Counter {
    var value = 0

    fun inc(): Int = ++value
    fun dec(): Int = --value
}

class CounterStructureTest {
    private val c = Counter()

    @Operation
    fun inc() = c.inc()

    @Operation
    fun dec() = c.dec()

    @Test
    fun test() = Lincheck.runConcurrentTest(this::class)

    @Test
    fun testWithRandomScenarios() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 100 // Specify the number of generated scenarios
        }
    }, this::class)

    @Test
    fun stressTest() = Lincheck.runConcurrentTest(LincheckSettings { stress() }, this::class)
}
