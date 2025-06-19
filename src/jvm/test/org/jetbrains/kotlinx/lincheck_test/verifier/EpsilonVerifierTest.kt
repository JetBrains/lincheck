/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.verifier

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

@StressCTest(iterations = 5, threads = 2, actorsPerThread = 2, verifier = EpsilonVerifier::class)
class EpsilonVerifierTest {
    private var i = 0

    @Operation
    fun incAndGet() = i++ // non-atomic!

    @Test
    fun test() = LinChecker.check(EpsilonVerifierTest::class.java)
}
