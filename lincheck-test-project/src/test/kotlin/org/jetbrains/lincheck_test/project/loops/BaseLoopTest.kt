package org.jetbrains.lincheck_test.project.loops

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import org.jetbrains.kotlinx.lincheck.execution.*
import kotlin.test.Test

abstract class BaseLoopTest {
    /**
     * Implement me and place the logic to check its trace.
     */
    @Operation
    abstract fun operation()

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::operation) }
            }
        }
        // to trigger the lincheck failure, we use the always failing verifier
        .verifier(FailingVerifier::class.java)
        .iterations(0)
        .apply { customize() }
        .check(this::class)

    open fun ModelCheckingOptions.customize() {}

}

class FailingVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?) = false
}
