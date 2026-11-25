package org.jetbrains.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.scenario.*


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

class InnerClassConstructorTest : AbstractSnapshotTest() {
    class InnerClassVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(a.c.value == 1)
            return true
        }
    }

    override fun LincheckSettings.customize() {
        scenarioGeneration {
            randomScenarios = 1
            invocationsPerScenario = 1
            verifier = InnerClassVerifier::class.java
        }
    }

    @Operation
    fun test() {
        val b = a.Inner()
        b.changeA()
    }
}