/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc

import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread


@OptIn(ExperimentalModelCheckingAPI::class)
class UserThreadExceptionTest {

    @Test
    fun testInfiniteBackgroundThreadError() {
        runConcurrentTest(10000) {
            val x = AtomicInteger()
            val t = thread {
                x.incrementAndGet()
                check(false)
                x.incrementAndGet()
            }
            x.incrementAndGet()
            t.join()
            check(x.get() == 2)
        }
    }
}