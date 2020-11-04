package org.jetbrains.kotlinx.lincheck.test

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

class BlockingOperationTest {
    private val counter = atomic(0)

    @Operation
    fun operation() {
        while (counter.value % 2 != 0) {}
    }

    @Operation(blocking = true)
    fun blocking(): Unit = synchronized(this) {}

    @Operation(blocking = true)
    fun leadsToBlocking() {
        counter.incrementAndGet()
        counter.incrementAndGet()
    }

    @Test
    fun test() = ModelCheckingOptions()
        .checkObstructionFreedom()
        .verifier(EpsilonVerifier::class.java)
        .requireStateEquivalenceImplCheck(false)
        .minimizeFailedScenario(false)
        .actorsBefore(0)
        .actorsAfter(0)
        .check(this::class)
}