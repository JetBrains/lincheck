/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.*

@StressCTest(iterations = 1, minimizeFailedScenario = false)
class OperationsInAbstractClassTest : AbstractTestClass() {
    @Test
    fun test(): Unit = LinChecker.check(this::class.java)
}

open class AbstractTestClass {
    @Operation
    fun operation(): Int = 0
}
