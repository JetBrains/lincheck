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

import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.*

class OperationsInAbstractClassTest : AbstractTestClass() {
    @Test
    fun test(): Unit = StressOptions()
        .iterations(1)
        .minimizeFailedScenario(false)
        .check(this::class)
}

open class AbstractTestClass {
    @Operation
    fun operation(): Int = 0
}
