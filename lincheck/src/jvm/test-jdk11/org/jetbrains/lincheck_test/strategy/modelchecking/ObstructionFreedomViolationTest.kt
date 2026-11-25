/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.strategy.modelchecking

import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.strategy.*
import org.jetbrains.lincheck_test.util.detectFailure
import org.junit.*

class ObstructionFreedomViolationTest { // Flaky on JDK 8
    private var c: Int = 0

    @Operation
    fun incAndGet(): Int = synchronized(this) { ++c }

    @Operation
    fun get(): Int = synchronized(this) { c }

    @Test
    fun test() {
        val settings = LincheckSettings {
            modelChecking { checkObstructionFreedom = true }
            scenarioGeneration { minimizeFailedScenario = false }
        }
        val failure = settings.detectFailure(ObstructionFreedomViolationTest::class.java)
        check(failure is ObstructionFreedomViolationFailure)
    }
}
