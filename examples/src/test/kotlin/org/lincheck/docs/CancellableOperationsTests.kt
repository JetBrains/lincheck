package org.lincheck.docs

import kotlinx.coroutines.channels.Channel
import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import kotlin.test.Test

@Param(name = "value", gen = IntGen::class, conf = "1:3")
class CancellableOnSuspensionTest {
    private val ch = Channel<Int>()

    @Operation
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation(cancellableOnSuspension = true)
    suspend fun receive() = ch.receive()

    @Test
    fun test() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 50
            invocationsPerScenario = 1000
        }
    }, this::class)
}

@Param(name = "value", gen = IntGen::class, conf = "1:3")
class PromptCancellationTest {
    private val ch = Channel<Int>()

    @Operation
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation(cancellableOnSuspension = true, promptCancellation = true)
    suspend fun receive() = ch.receive()

    @Test
    fun test() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 50
            invocationsPerScenario = 1000
        }
    }, this::class)
}
