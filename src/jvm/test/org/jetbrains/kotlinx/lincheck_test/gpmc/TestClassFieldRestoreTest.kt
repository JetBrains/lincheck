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

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.junit.Test
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

class TestClassFieldRestoreTest {
    private val lock = ReentrantLock()
    private var inc = 0

    @Test
    fun test() {
        runConcurrentTest {
            // an incrementing variable to make sure that it is
            // restored to initial value between invocations
            check(inc == 0)
            inc++

            lock.lock()
            val t = thread {
                lock.lock()
                lock.unlock()
            }
            lock.unlock()
            t.join()
        }
    }
}