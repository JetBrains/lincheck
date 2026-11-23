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

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread
import java.util.function.Supplier

/** Objects missed by allocation instrumentation must remain accessible during exploration. */
class UnregisteredObjectsTest {

    private enum class Color { RED, GREEN }

    @Test
    fun testEnumValuesArrayRead() {
        // Each thread clones the synthetic `$VALUES` array independently and indexes it directly, so the
        // outcome does not depend on scheduling: the only point of this test is that reading it does not crash.
        litmusTest(assertSame(setOf("RED" to "GREEN"))) {
            lateinit var r0: String
            lateinit var r1: String
            val t1 = thread { r0 = Color.values()[0].name }
            val t2 = thread { r1 = Color.values()[1].name }
            t1.join()
            t2.join()
            r0 to r1
        }
    }

    private class CapturingLambdaHolder {
        var calls: Int = 0
        val task: Supplier<Int> = Supplier { calls + 1 }
    }

    @Test
    fun testLambdaCapturedBeforeExecution() {
        val holder = CapturingLambdaHolder()
        for (model in listOf(MemoryModel.SequentialConsistency, MemoryModel.RelaxedJava)) {
            litmusTest(
                assertSame(setOf(1, 2)), model,
                requireExhaustion = true,
                checkStats = { assertTrue("Expected replay across schedules", it.totalInvocations > 1) },
            ) {
                var result = -1
                val t1 = thread { result = holder.task.get() }
                val t2 = thread { result = holder.task.get() + 1 }
                t1.join()
                t2.join()
                result
            }
        }
    }
}
