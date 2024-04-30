/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck_test.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Checks that the AtomicLong.VMSupportsCS8() native method
 * is correctly transformed.
 */
class AtomicLongTest : AbstractLincheckTest() {
    val counter = AtomicLong()

    @Operation
    fun inc() = counter.incrementAndGet()
}