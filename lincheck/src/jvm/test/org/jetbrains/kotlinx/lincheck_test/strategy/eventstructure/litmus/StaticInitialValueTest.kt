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
import org.junit.Test
import kotlin.concurrent.thread

class StaticInitialValueTest {
    private object State {
        @JvmField var number = 0
        @JvmField var reference: String? = null
    }

    @Test
    fun primitiveInitialValueSurvivesWritesAndReplay() {
        for (model in MemoryModel.entries) {
            for (initial in listOf(7, 11)) {
                // Initialize outside the tracked execution so the read must also consider the root event.
                State.number = initial
                litmusTest(assertSame(setOf(initial, 42)), model, requireExhaustion = true) {
                    var result = -1
                    val writer = thread { State.number = 42 }
                    val reader = thread { result = State.number }
                    writer.join()
                    reader.join()
                    result
                }
            }
        }
    }

    @Test
    fun nullInitialValueSurvivesWritesAndReplay() {
        for (model in MemoryModel.entries) {
            State.reference = null
            litmusTest(assertSame(setOf(null, "written")), model, requireExhaustion = true) {
                var result: String? = "unread"
                val writer = thread { State.reference = "written" }
                val reader = thread { result = State.reference }
                writer.join()
                reader.join()
                result
            }
        }
    }
}
