  package org.jetbrains.kotlinx.lincheck.test

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.*

class ThreadIdTest : AbstractLincheckTest() {
    private val balances = IntArray(5)
    private val counter = atomic(0)

    @Operation
    fun inc(@Param(gen = ThreadIdGen::class) threadId: Int): Int = counter.incrementAndGet()
        .also { balances[threadId]++ }

    @Operation
    fun decIfNotNegative(@Param(gen = ThreadIdGen::class) threadId: Int) {
        if (balances[threadId] == 0) return
        balances[threadId]--
        val c = counter.decrementAndGet()
        if (c < 0) error("The counter cannot be negative")
    }

    override fun extractState() = balances.toList() to counter.value
}