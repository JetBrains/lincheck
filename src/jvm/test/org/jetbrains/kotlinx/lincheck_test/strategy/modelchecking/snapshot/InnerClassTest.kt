package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions


private class Outer {
    class C(@JvmField var value: Int)

    @JvmField
    var c = C(1)

    inner class Inner {
        val linkToOuterValue: C
        init {
            linkToOuterValue = this@Outer.c
        }

        fun changeA() {
            linkToOuterValue.value = 2
        }
    }
}

private val a = Outer()

class InnerClassTest : SnapshotAbstractTest() {
    class InnerClassVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(a.c.value == 1)
            return true
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        if (this is ModelCheckingOptions) invocationsPerIteration(1)
        verifier(InnerClassVerifier::class.java)
    }

    @Operation
    fun test() {
        val b = a.Inner()
        b.changeA()
    }
}