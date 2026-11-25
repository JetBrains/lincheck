/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.verifier

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.verifier.EpsilonVerifier
import org.junit.*

class EpsilonVerifierTest {
    private var i = 0

    @Operation
    fun incAndGet() = i++ // non-atomic!

    @Test
    fun test() = runConcurrentTest(LincheckSettings {
        stress()
        scenarioGeneration { verifier = EpsilonVerifier::class.java }
    }, this::class)
}
