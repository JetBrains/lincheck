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
import java.util.*
import java.util.concurrent.*

/**
 * Checks that [Random] and [ThreadLocalRandom] are replaced
 * with deterministic implementations in the model checking mode.
 */
@ModelCheckingCTest(iterations = 50, invocationsPerIteration = 1000)
class RandomTransformationTest : VerifierState() {
    @Volatile
    private var a: Any = Any()

    @Operation
    fun random() {
        if (Random().nextInt() % 3 == 2) {
            // just add some code locations
            a = Any()
            a = Any()
        }
    }

    @Operation
    fun threadLocalRandom() {
        if (ThreadLocalRandom.current().nextInt() % 3 == 2) {
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
