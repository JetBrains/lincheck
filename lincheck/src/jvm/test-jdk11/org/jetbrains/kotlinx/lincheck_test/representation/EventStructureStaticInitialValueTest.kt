/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.detectFailure
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.getValue
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import org.junit.Test

class EventStructureStaticInitialValueTest {
    private object State {
        @JvmField var value = 7
    }

    @Operation
    fun write() { State.value = 42 }

    @Operation
    fun read(): Int = State.value

    @Test
    fun test() {
        State.value = 7
        ModelCheckingOptions()
            .useExperimentalModelChecking()
            .iterations(0)
            .addCustomScenario {
                parallel {
                    thread { actor(::write) }
                    thread { actor(::read) }
                }
            }
            .verifier(InitialValueVerifier::class.java)
            .detectFailure(this::class.java) { failure ->
                failure.checkLincheckOutput("event_structure_static_initial_value")
            }
    }
}

// Select the execution that reads from initialization after exploring the competing write.
class InitialValueVerifier(@Suppress("UNUSED_PARAMETER") testClass: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean =
        getValue<Int>(results!!.parallelResults[1].single()!!) != 7
}
