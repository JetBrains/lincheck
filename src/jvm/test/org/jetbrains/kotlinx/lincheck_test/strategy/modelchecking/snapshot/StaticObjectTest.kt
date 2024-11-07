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
import java.util.concurrent.atomic.AtomicInteger


private var staticInt = AtomicInteger(1)

class StaticObjectTest : SnapshotAbstractTest() {
    companion object {
        private var ref: AtomicInteger = staticInt
        private var value: Int = staticInt.get()
    }

    class StaticObjectVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            check(staticInt == ref)
            check(staticInt.get() == value)
            return true
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        verifier(StaticObjectVerifier::class.java)
    }

    @Operation
    fun modify() {
        staticInt.getAndIncrement()
    }
}