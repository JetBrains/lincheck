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

import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.appendFailure
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

/**
 * This test checks that a hint to reproduce failure is present in output.
 */
@Suppress("unused")
class ReproduceSettingsRepresentationTest {

    @Volatile
    private var counter = 0

    @Operation
    fun increment(): Int = counter++

    @Operation
    fun get(): Int = counter

    @Test
    fun test() {
        val failure = ModelCheckingOptions().checkImpl(this::class.java) ?: error("Test should fail")
        val output = StringBuilder().appendFailure(failure).toString()
        val regex = Regex(
            ".*\n= To reproduce exactly this test execution with the same scenarios, you can add this setting in your testing options configuration =\n" +
                    ".withReproduceSettings(.+).*",
            RegexOption.DOT_MATCHES_ALL
        )

        assertTrue(regex matches output)
    }

}