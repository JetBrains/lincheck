/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmus

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSame
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmus.ArrayCloning.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class ArrayCloneReplayTest {
    @Test
    fun enumValuesReplay() {
        for (model in listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)) {
            litmusTest(
                assertSame(setOf("RED", "GREEN")), model,
                requireExhaustion = true,
                checkStats = { assertTrue("Expected replay across schedules", it.totalInvocations > 1) },
            ) {
                var result = ""
                val t1 = thread { result = Color.values()[0].name }
                val t2 = thread { result = Color.values()[1].name }
                t1.join()
                t2.join()
                result
            }
        }
    }

    @Test
    fun primitiveArrayCloneReplay() {
        for (model in listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)) {
            litmusTest(
                assertSame(setOf(1, 2)), model,
                requireExhaustion = true,
                checkStats = { assertTrue("Expected replay across schedules", it.totalInvocations > 1) },
            ) {
                val source = intArrayOf(0)
                var result = -1
                val t1 = thread {
                    val clone = ArrayCloning.clone(source)
                    clone[0] = 1
                    result = clone[0]
                }
                val t2 = thread {
                    val clone = ArrayCloning.clone(source)
                    clone[0] = 2
                    result = clone[0]
                }
                t1.join()
                t2.join()
                check(source[0] == 0)
                result
            }
        }
    }
}
