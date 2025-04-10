/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.channels

import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.FixedThreadPoolCoroutineTest
import org.junit.Test

abstract class BaseChannelTest(
    shouldFail: Boolean = false,
    invocations: Int = 1000,
    nThreads: Int = 2,
) : FixedThreadPoolCoroutineTest(shouldFail, invocations, nThreads) {

    abstract fun block(dispatcher: CoroutineDispatcher)

    @Test
    fun testChannel() {
        executeCoroutineTest(this::block)
    }
}