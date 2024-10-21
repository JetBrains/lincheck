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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.junit.After
import java.util.concurrent.atomic.AtomicInteger


private var staticInt = AtomicInteger(1)

class StaticObjectTest : SnapshotAbstractTest() {
    @Operation
    fun modify() {
        staticInt.getAndIncrement()
    }

    private var ref = staticInt
    private var value = staticInt.get()

    @After
    fun checkStaticStateRestored() {
        check(staticInt == ref)
        check(staticInt.get() == value)
    }
}