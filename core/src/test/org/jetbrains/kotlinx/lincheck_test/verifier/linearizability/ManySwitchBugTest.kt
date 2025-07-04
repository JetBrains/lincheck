/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.*

/**
 * This test checks that model checking strategy can find a many switch bug.
 */
class ManySwitchBugTest {
    private var canEnterSection1 = false
    private var canEnterSection2 = false
    private var canEnterSection3 = false
    private var canEnterSection4 = false
    private var canEnterSection5 = false

    @Operation
    fun foo() {
        canEnterSection1 = true
        canEnterSection1 = false
        if (canEnterSection2) {
            canEnterSection3 = true
            canEnterSection3 = false
            if (canEnterSection4) {
                canEnterSection5 = true
                canEnterSection5 = false
            }
        }
    }

    @Operation
    fun bar() {
        if (canEnterSection1) {
            canEnterSection2 = true
            canEnterSection2 = false
            if (canEnterSection3) {
                canEnterSection4 = true
                canEnterSection4 = false
                if (canEnterSection5) error("Can't enter here")
            }
        }
    }

    @Test
    fun test() {
        val failure = ModelCheckingOptions()
            .actorsAfter(0)
            .actorsBefore(0)
            .actorsPerThread(1)
            .checkImpl(this::class.java)
        check(failure is IncorrectResultsFailure) { "The test should fail" }
    }
}
