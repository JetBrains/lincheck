/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.transformation

import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.forClasses
import org.jetbrains.lincheck_test.util.detectFailure
import org.junit.*

/**
 * This test checks methods with `ignored` guarantee are handled correctly when exception occurs,
 * i.e. ignored section ends.
 */
class IgnoredGuaranteeOnExceptionTest {
    private var counter = 0

    @Operation
    fun inc() = try {
        badMethod()
    } catch(e: Throwable) {
        counter++
        counter++
    }

    private fun badMethod(): Int = TODO()

    @Test
    fun test() {
        val settings = LincheckSettings {
            modelChecking { addGuarantee(forClasses(IgnoredGuaranteeOnExceptionTest::class).methods("badMethod").ignore()) }
        }
        val failure = settings.detectFailure(this.javaClass)
        check(failure != null) { "This test should fail" }
    }
}
