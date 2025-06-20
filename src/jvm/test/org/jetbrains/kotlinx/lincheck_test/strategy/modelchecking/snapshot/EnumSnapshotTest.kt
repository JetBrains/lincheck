/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.lincheck.datastructures.ManagedOptions


private enum class Values {
    A, B, C;
}
private class EnumHolder(var x: Values, var y: Values)

private var global = EnumHolder(Values.A, Values.B)

class EnumSnapshotTest : AbstractSnapshotTest() {
    companion object {
        private var initA: EnumHolder = global
        private var initX: Values = global.x
        private var initY: Values = global.y
    }

    class StaticEnumVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(global == initA)
            check(global.x == initX)
            check(global.y == initY)
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(StaticEnumVerifier::class.java)
    }

    @Operation
    fun modifyFields() {
        // modified fields of the initial instance
        global.x = Values.B
        global.y = Values.C

        // assign different instance to the variable
        global = EnumHolder(Values.C, Values.C)
    }
}