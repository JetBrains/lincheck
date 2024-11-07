/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

abstract class SnapshotAbstractTest {
    open fun <O : Options<O, *>> O.customize() {}

    @Test
    fun testModelChecking() = ModelCheckingOptions()
//        .logLevel(LoggingLevel.INFO)
        .iterations(1)
        .actorsBefore(0)
        .actorsAfter(0)
        .actorsPerThread(2)
        .restoreStaticMemory(true)
        .apply { customize() }
        .check(this::class)
}