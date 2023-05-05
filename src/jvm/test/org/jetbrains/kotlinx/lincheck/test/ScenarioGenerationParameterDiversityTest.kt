/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

/**
 * This test checks that parameters in generated scenarios are diversified
 */
@Param(name = "value", gen = IntGen::class)
class ScenarioGenerationParameterDiversityTest : VerifierState() {
    @Operation
    fun foo(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
        check(setOf(a, b, c, d, e, f).size > 1) { "At least 2 parameters should be different w.h.p."}
    }

    @Operation(params = ["value", "value", "value", "value", "value", "value"])
    fun bar(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
        check(setOf(a, b, c, d, e, f).size > 1) { "At least 2 parameters should be different w.h.p."}
    }

    @Test
    fun test() {
        StressOptions()
            .invocationsPerIteration(1)
            .iterations(100)
            .threads(1)
            .checkImpl(this::class.java)
    }

    override fun extractState(): Any = 0 // constant state
}
