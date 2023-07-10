/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.util.concurrent.atomic.*

class ValidateFunctionTest : VerifierState() {
    val c = AtomicInteger()

    @Operation
    fun inc() = c.incrementAndGet()

    override fun extractState() = c.get()

    @Validate
    fun validateNoError() {
    }

    var validateInvoked: Int = 0

    // This function fails on the 5ht invocation
    @Validate
    fun validateWithError() {
        validateInvoked++
        if (validateInvoked == 5) error("Validation works!")
    }

    @Test
    fun test() = ModelCheckingOptions().apply {
        iterations(1)
        invocationsPerIteration(1)
        actorsBefore(3)
        actorsAfter(10)
        withReproduceSettings("eyJyYW5kb21TZWVkR2VuZXJhdG9yU2VlZCI6LTQ1NjkxMTY2MzQ1OTkxNDk3ODR9")
    }
        .checkImpl(this::class.java)
        .checkLincheckOutput("validation_function_failure.txt")

}
