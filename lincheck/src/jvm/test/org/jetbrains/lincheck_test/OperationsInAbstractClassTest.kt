/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.*

class OperationsInAbstractClassTest : AbstractTestClass() {
    @Test
    fun test(): Unit = runConcurrentTest(LincheckSettings {
        stress()
        scenarioGeneration {
            randomScenarios = 1
            minimizeFailedScenario = false
        }
    }, this::class)
}

open class AbstractTestClass {
    @Operation
    fun operation(): Int = 0
}
