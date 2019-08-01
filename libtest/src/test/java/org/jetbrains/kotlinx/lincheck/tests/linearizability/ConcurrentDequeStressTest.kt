package org.jetbrains.kotlinx.lincheck.tests.linearizability

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.lang.AssertionError
import java.util.concurrent.ConcurrentLinkedDeque

@Param(name = "value", gen = IntGen::class, conf = "1:5")
@StressCTest(actorsPerThread = 10, threads = 2, invocationsPerIteration = 10000, iterations = 10, actorsBefore = 10, actorsAfter = 10)
class ConcurrentDequeStressTest : VerifierState() {

    val deque = ConcurrentLinkedDeque<Int>()

    @Operation
    fun addFirst(e: Int) = deque.addFirst(e)

    @Operation
    fun addLast(e: Int) = deque.addLast(e)

    @Operation
    fun pollFirst() = deque.pollFirst()

    @Operation
    fun pollLast() = deque.pollLast()

    @Test(expected = AssertionError::class)
    fun test() = LinChecker.check(ConcurrentDequeStressTest::class.java)

    override fun extractState() = deque.toList()
}
