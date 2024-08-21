/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.scenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test

/**
 * Failing test that checks that output using [outputFileName].
 * The goal is to place the logic to check trace in the [actionsForTrace] method.
 */
abstract class BaseFailingTest(private val outputFileName: String) {

    @Operation
    fun operation(): Int {
        actionsForTrace()
        return 1
    }

    /**
     * Implement me and place the logic to check its trace.
     */
    abstract fun actionsForTrace()

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::operation) }
            }
        }
        // to trigger the lincheck failure, we set the specification class
        // where the `operation` method always returns a different result
        .sequentialSpecification(BaseFailingTestSpecification::class.java)
        .iterations(0)
        .apply { customize() }
        .checkImpl(this::class.java)
        .checkLincheckOutput(outputFileName)

    open fun ModelCheckingOptions.customize() {}

}

class BaseFailingTestSpecification {
    fun operation(): Int = 0
}