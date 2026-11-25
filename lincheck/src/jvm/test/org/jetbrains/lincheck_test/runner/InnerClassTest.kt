/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.runner

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.Test

class OuterClass {
    inner class InnerClass {
        @Operation
        fun zero() = 0
    }

    class NestedClass {
        @Operation
        fun zero() = 0
    }
}

class InnerClassTest {
    @Test
    fun testInnerClass() {
        runConcurrentTest(LincheckSettings {
            stress()
            scenarioGeneration {
                invocationsPerScenario = 1
                randomScenarios = 1
            }
        }, OuterClass.InnerClass::class)
    }

    @Test
    fun testNestedClass() {
        runConcurrentTest(LincheckSettings {
            stress()
            scenarioGeneration {
                invocationsPerScenario = 1
                randomScenarios = 1
            }
        }, OuterClass.NestedClass::class)
    }
}
