package org.jetbrains.kotlinx.lincheck.tests.linearizability

import kotlinx.coroutines.sync.Mutex
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.annotations.LogLevel
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.lang.IllegalStateException

@Param(name = "value", gen = IntGen::class, conf = "1:5")
@StressCTest(actorsPerThread = 10, threads = 2, invocationsPerIteration = 10000, iterations = 100, actorsBefore = 10, actorsAfter = 0)
class MutexStressTest : VerifierState() {
    val mutex = Mutex(true)

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun tryLock(e: Int) = mutex.tryLock(e)

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    suspend fun lock(e: Int) = mutex.lock(e)

    @Operation
    fun holdsLock(e: Int) = mutex.holdsLock(e)

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun unlock(e: Int) = mutex.unlock(e)

    @Test
    fun test() = LinChecker.check(MutexStressTest::class.java)

    override fun extractState() = mutex
}