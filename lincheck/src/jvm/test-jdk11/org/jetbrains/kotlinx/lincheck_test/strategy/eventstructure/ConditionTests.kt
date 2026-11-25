/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.junit.Test
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Tests of `java.util.concurrent.locks.Condition`.
 *
 * A node of a condition queue clears its status bits with `Unsafe.getAndBitwiseAndInt` (JDK 17, JDK 21):
 * a waiting thread observes a signal only if the checker sees this update.
 */
class ConditionTests {

    @Test
    fun testAwaitSignal() = checkAwait(signalAll = false)

    @Test
    fun testAwaitSignalAll() = checkAwait(signalAll = true)

    private fun checkAwait(signalAll: Boolean) {
        for (memoryModel in listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)) {
            // The executions are too many to explore them all; the first one already has the waiter woken up by the signal.
            litmusTest(assertSame(setOf(1), UNKNOWN), memoryModel, invocations = 100) {
                val lock = ReentrantLock()
                val condition = lock.newCondition()
                var flag = false
                var result = 0
                val waiter = thread {
                    lock.lock()
                    try {
                        while (!flag) condition.await()
                        result = 1
                    } finally {
                        lock.unlock()
                    }
                }
                val signaller = thread {
                    lock.lock()
                    try {
                        flag = true
                        if (signalAll) condition.signalAll() else condition.signal()
                    } finally {
                        lock.unlock()
                    }
                }
                waiter.join()
                signaller.join()
                result
            }
        }
    }
}
