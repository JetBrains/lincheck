/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.junit.*

/**
 * This test checks that transformed code supports reentrant synchronized locking.
 */
class NestedSynchronizedBlocksTest {
    private var counter = 0

    @Operation
    fun inc() = synchronized(this) {
            synchronized(this) {
                counter++
            }
        }

    @Test
    fun test() = ModelCheckingOptions()
        .iterations(1)
        .check(this::class)
}
