package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*

@StressCTest(iterations = 1, actorsBefore = 0, actorsAfter = 0,
             requireStateEquivalenceImplCheck = false, minimizeFailedScenario = false)
class HangingTest {
    @Operation
    fun badOperation() {
        while (true) {}
    }

    @Test(expected = AssertionError::class)
    fun test() = LinChecker.check(this::class.java)
}