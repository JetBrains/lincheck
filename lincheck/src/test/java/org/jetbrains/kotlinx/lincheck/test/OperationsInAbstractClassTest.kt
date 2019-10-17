package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*
import java.lang.AssertionError
import kotlin.random.*

@StressCTest(iterations = 1, minimizeFailedScenario = false, requireStateEquivalenceImplCheck = false)
class OperationsInAbstractClassTest : AbstractTestClass() {
    @Operation
    fun goodOperation() = 10

    @Test(expected = AssertionError::class)
    fun test(): Unit = LinChecker.check(this::class.java)
}

open class AbstractTestClass {
    @Operation
    fun badOperation() = Random.nextInt(10)
}