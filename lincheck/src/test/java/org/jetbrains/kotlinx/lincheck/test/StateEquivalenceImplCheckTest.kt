package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.junit.Test
import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicInteger

@StressCTest
class StateEquivalenceImplCheckTest {
    private var i = AtomicInteger(0)

    @Operation
    fun incAndGet() = i.incrementAndGet()

    // Check that IllegalStateException is thrown if `requireStateEquivalenceImplCheck` option is true by default
    // and hashCode/equals methods are not implemented
    @Test(expected = IllegalStateException::class)
    fun test() = LinChecker.check(StateEquivalenceImplCheckTest::class.java)
}