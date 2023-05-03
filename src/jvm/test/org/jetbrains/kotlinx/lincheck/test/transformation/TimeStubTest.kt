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
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

/**
 * Checks that [System.nanoTime] and [System.currentTimeMillis] are
 * replaced with deterministic implementations in the model checking mode.
 */
@ModelCheckingCTest(iterations = 30, invocationsPerIteration = 1000)
class TimeStubTest : VerifierState() {
    @Volatile
    private var a: Any = Any()

    @Operation
    fun nanoTime() {
        if (System.nanoTime() % 3L == 2L) {
            // just add some code locations
            a = Any()
            a = Any()
        }
    }

    @Operation
    fun currentTimeMillis() {
        if (System.currentTimeMillis() % 3L == 2L) {
            // just add some code locations
            a = Any()
            a = Any()
        }
    }

    @Test
    fun test() {
        LinChecker.check(this::class.java)
    }

    override fun extractState(): Any = 0 // constant state

}
