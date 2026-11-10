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
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.OutcomeVerifier
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import kotlin.concurrent.thread

/** Release chains and volatile fences mixed with plain and opaque accesses. */
class MixedAccessMemoryModelTests {
    @Test
    fun ReleaseChain() {
        litmusTest(mixedAccessWitness("ReleaseChain", Triple(1, 2, 1)), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val cell = HandleCell()
            var first = -1
            var second = -1
            var third = -1
            val publisher = thread {
                HandleCell.X.set(cell, 1)
                HandleCell.Y.setRelease(cell, 1)
            }
            val relay = thread {
                first = HandleCell.Y.get(cell) as Int
                HandleCell.X.setRelease(cell, 2)
            }
            val reader = thread {
                second = HandleCell.X.getOpaque(cell) as Int
                third = HandleCell.X.getOpaque(cell) as Int
            }
            publisher.join()
            relay.join()
            reader.join()
            Triple(first, second, third)
        }
    }

    @Test
    fun VolatileFenceVarHandle() {
        litmusTest(mixedAccessWitness("VolatileFenceVarHandle", 0 to 0), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val cell = HandleCell()
            var first = -1
            var second = -1
            val firstActor = thread {
                HandleCell.X.set(cell, 1)
                HandleCell.FLAG.setVolatile(cell, 42)
                HandleCell.FLAG.getVolatile(cell)
                first = HandleCell.Y.get(cell) as Int
            }
            val secondActor = thread {
                HandleCell.Y.set(cell, 1)
                HandleCell.FLAG.setVolatile(cell, 42)
                HandleCell.FLAG.getVolatile(cell)
                second = HandleCell.X.get(cell) as Int
            }
            firstActor.join()
            secondActor.join()
            first to second
        }
    }

    @Test
    fun VolatileFence() {
        litmusTest(mixedAccessWitness("VolatileFence", 0 to 0), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val cell = VolatileCell()
            var first = -1
            var second = -1
            val firstActor = thread {
                cell.x = 1
                cell.flag = 42
                @Suppress("UNUSED_VARIABLE")
                val unused = cell.flag
                first = cell.y
            }
            val secondActor = thread {
                cell.y = 1
                cell.flag = 42
                @Suppress("UNUSED_VARIABLE")
                val unused = cell.flag
                second = cell.x
            }
            firstActor.join()
            secondActor.join()
            first to second
        }
    }

    @Test
    fun VolatileFenceWithReadbacks() {
        litmusTest(mixedAccessWitness("VolatileFenceWithReadbacks", listOf(42, 42, 0, 0)), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val cell = VolatileCell()
            var firstFlag = -1
            var secondFlag = -1
            var first = -1
            var second = -1
            val firstActor = thread {
                cell.x = 1
                cell.flag = 42
                firstFlag = cell.flag
                first = cell.y
            }
            val secondActor = thread {
                cell.y = 1
                cell.flag = 42
                secondFlag = cell.flag
                second = cell.x
            }
            firstActor.join()
            secondActor.join()
            listOf(firstFlag, secondFlag, first, second)
        }
    }

    private fun <T> mixedAccessWitness(name: String, witness: T): OutcomeVerifier<T> = OutcomeVerifier { outcomes ->
        assertTrue("No completed execution for $name", outcomes.isNotEmpty())
        println("LITMUS_WITNESS $name observed=${witness in outcomes} witness=$witness")
    }

    private class HandleCell {
        @JvmField var x = 0
        @JvmField var y = 0
        @JvmField var flag = 0

        companion object {
            val X: VarHandle = MethodHandles.lookup().findVarHandle(HandleCell::class.java, "x", Int::class.javaPrimitiveType)
            val Y: VarHandle = MethodHandles.lookup().findVarHandle(HandleCell::class.java, "y", Int::class.javaPrimitiveType)
            val FLAG: VarHandle = MethodHandles.lookup().findVarHandle(HandleCell::class.java, "flag", Int::class.javaPrimitiveType)
        }
    }

    private class VolatileCell {
        @JvmField var x = 0
        @JvmField var y = 0
        @JvmField @Volatile var flag = 0
    }
}
