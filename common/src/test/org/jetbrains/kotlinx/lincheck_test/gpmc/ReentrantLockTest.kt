/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc

import java.util.concurrent.locks.*
import kotlin.concurrent.*
import org.junit.Test

class ReentrantLockTest {

    fun reentrantLock(): Int {
        var counter = 0
        val lock = ReentrantLock()
        val t1 = thread {
            lock.withLock { counter++ }
        }
        val t2 = thread {
            lock.withLock { counter++ }
        }
        val t3 = thread {
            lock.withLock { counter++ }
        }
        return lock.withLock { counter }
            .also { listOf(t1, t2, t3).forEach { it.join() } }
    }

    @Test(timeout = TIMEOUT)
    fun testReentrantLock() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::reentrantLock,
        expectedOutcomes = setOf(0, 1, 2, 3)
    )

}