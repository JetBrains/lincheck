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

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.verifier.Verifier

private class A(var b: B)
private class B(var a: A? = null)

private var globalA = A(B())

class StaticObjectCycleTest : SnapshotAbstractTest() {
    companion object {
        private var initA = globalA
        private var initB = globalA.b

        init {
            globalA.b.a = globalA
        }
    }

    class StaticObjectCycleVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            check(globalA == initA)
            check(globalA.b == initB)
            check(globalA.b.a == globalA)
            return true
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        verifier(StaticObjectCycleVerifier::class.java)
    }

    @Operation
    fun modify() {
        globalA = A(B())
    }
}