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

import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

// TODO: make each test check values on each invocation and not on test end
abstract class SnapshotAbstractTest {
    @Test
    fun testModelChecking() = ModelCheckingOptions()
        .iterations(1)
        .actorsBefore(0)
        .actorsAfter(0)
        .actorsPerThread(3)
        .restoreStaticMemory(true)
        .check(this::class)
}