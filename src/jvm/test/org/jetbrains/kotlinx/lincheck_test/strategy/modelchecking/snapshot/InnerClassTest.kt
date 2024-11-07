package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.kotlinx.lincheck.ExceptionResult
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.Verifier


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
    class InnerClassVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            results?.parallelResults?.forEach { threadsResults ->
                threadsResults.forEach { result ->
                    if (result is ExceptionResult) {
                        throw result.throwable
                    }
                }
            }

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