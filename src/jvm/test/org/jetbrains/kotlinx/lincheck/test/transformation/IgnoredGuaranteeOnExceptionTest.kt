/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

/**
 * This test checks methods with `ignored` guarantee are handled correctly when exception occurs,
 * i.e. ignored section ends.
 */
class IgnoredGuaranteeOnExceptionTest : VerifierState() {
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
        val options = ModelCheckingOptions().addGuarantee(forClasses(this.javaClass.name).methods("badMethod").ignore())
        val failure = options.checkImpl(this.javaClass)
        check(failure != null) { "This test should fail" }
    }

    override fun extractState(): Any = counter
}
