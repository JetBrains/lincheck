/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.junit.Test
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.concurrent.thread

class VolatileAccessesMemoryModelTests {

    @Test
    fun testStoreBufferingWithOneFullFenceAndVolatileAccesses() {
        litmusTest(assertNever(setOf(0 to 0)), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0
            var r1 = 0
            val t0 = thread {
                x.setOpaque(1)
                VarHandle.fullFence()
                r0 = y.getOpaque()
            }
            val t1 = thread {
                y.set(1)
                r1 = x.get()
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    @Test
    fun testFullFenceRetainsWritesSubsumedByReads() {
        litmusTest(assertNever(setOf(Triple(1, 0, 0))), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            var r2 = -1
            val t0 = thread {
                x.setOpaque(1)
                // This read subsumes the write in the lower eco frontier.
                r0 = x.getOpaque()
                VarHandle.fullFence()
                r1 = y.getOpaque()
            }
            val t1 = thread {
                y.set(1)
                r2 = x.get()
            }
            t0.join()
            t1.join()
            Triple(r0, r1, r2)
        }
    }

    @Test
    fun testFullFencesWithReadsFromTheSameWrite() {
        litmusTest(assertSame(setOf(listOf(0, 0, 0, 0))), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            var r2 = -1
            var r3 = -1
            val t0 = thread {
                r0 = x.getOpaque()
                VarHandle.fullFence()
                r1 = x.getOpaque()
            }
            val t1 = thread {
                r2 = x.getOpaque()
                VarHandle.fullFence()
                r3 = x.getOpaque()
            }
            t0.join()
            t1.join()
            listOf(r0, r1, r2, r3)
        }
    }

    @Test
    fun testFullFencesWithPlainReadsFromTheSameWrite() {
        litmusTest(assertSame(setOf(listOf(0, 0, 0, 0))), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            var r2 = -1
            var r3 = -1
            val t0 = thread {
                r0 = x.getPlain()
                VarHandle.fullFence()
                r1 = x.getPlain()
            }
            val t1 = thread {
                r2 = x.getPlain()
                VarHandle.fullFence()
                r3 = x.getPlain()
            }
            t0.join()
            t1.join()
            listOf(r0, r1, r2, r3)
        }
    }

    @Test
    fun testFullFenceWithIndependentVolatileArrayElements() {
        litmusTest(assertSame(setOf(0)), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val values = AtomicIntegerArray(2)
            values.set(0, 1)
            VarHandle.fullFence()
            values.get(1)
        }
    }

    @Test
    fun testFullFenceOrdersPlainReads() {
        val outcomes = (0..2).flatMap { first -> (first..2).map { second -> first to second } }.toSet()
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            val writer = thread {
                x.setOpaque(1)
                x.setOpaque(2)
            }
            val reader = thread {
                r0 = x.getPlain()
                VarHandle.fullFence()
                r1 = x.getPlain()
            }
            writer.join()
            reader.join()
            r0 to r1
        }
    }

    @Test
    fun testFullFenceDoesNotTreatReadSourcesAsPrecedingWrites() {
        litmusTest(assertSometimes(setOf(Triple(1, 0, 0))), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            var r2 = -1
            val t0 = thread {
                r0 = x.getOpaque()
                VarHandle.fullFence()
                r1 = y.getOpaque()
            }
            val t1 = thread {
                y.set(1)
                r2 = x.get()
            }
            val t2 = thread { x.setOpaque(1) }
            t0.join()
            t1.join()
            t2.join()
            Triple(r0, r1, r2)
        }
    }

    @Test
    fun testIRIWWithFullFences() {
        litmusTest(assertNever(setOf(listOf(1, 0, 1, 0))), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            var r2 = -1
            var r3 = -1
            val writer0 = thread { x.setOpaque(1) }
            val writer1 = thread { y.setOpaque(1) }
            val reader0 = thread {
                r0 = x.getOpaque()
                VarHandle.fullFence()
                r1 = y.getOpaque()
            }
            val reader1 = thread {
                r2 = y.getOpaque()
                VarHandle.fullFence()
                r3 = x.getOpaque()
            }
            writer0.join()
            writer1.join()
            reader0.join()
            reader1.join()
            listOf(r0, r1, r2, r3)
        }
    }

    @Test
    fun testIRIWWithPlainReadsAndFullFences() {
        litmusTest(assertNever(setOf(listOf(1, 0, 1, 0))), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            var r2 = -1
            var r3 = -1
            val writer0 = thread { x.setOpaque(1) }
            val writer1 = thread { y.setOpaque(1) }
            val reader0 = thread {
                r0 = x.getPlain()
                VarHandle.fullFence()
                r1 = y.getPlain()
            }
            val reader1 = thread {
                r2 = y.getPlain()
                VarHandle.fullFence()
                r3 = x.getPlain()
            }
            writer0.join()
            writer1.join()
            reader0.join()
            reader1.join()
            listOf(r0, r1, r2, r3)
        }
    }

    @Test
    fun testIRIWWithPlainAndOpaqueReadsAfterFullFence() {
        litmusTest(assertNever(setOf(listOf(1, 0, 0, 1, 0))), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            var r2 = -1
            var r3 = -1
            var r4 = -1
            val writer0 = thread { x.setOpaque(1) }
            val writer1 = thread { y.setOpaque(1) }
            val reader0 = thread {
                r0 = x.getOpaque()
                VarHandle.fullFence()
                r1 = y.getPlain()
                // This read must remain a witness even when the plain read has the same source.
                r2 = y.getOpaque()
            }
            val reader1 = thread {
                r3 = y.getPlain()
                VarHandle.fullFence()
                r4 = x.getOpaque()
            }
            writer0.join()
            writer1.join()
            reader0.join()
            reader1.join()
            listOf(r0, r1, r2, r3, r4)
        }
    }

    @Test
    fun testIRIWWithPlainAndOpaqueReadsBeforeFullFence() {
        litmusTest(assertNever(setOf(listOf(1, 0, 1, 1, 0))), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            var r2 = -1
            var r3 = -1
            var r4 = -1
            val writer0 = thread { x.setOpaque(1) }
            val writer1 = thread { y.setOpaque(1) }
            val reader0 = thread {
                r0 = x.getOpaque()
                VarHandle.fullFence()
                r1 = y.getPlain()
            }
            val reader1 = thread {
                r2 = y.getPlain()
                // This read must remain a witness even when the plain read has the same source.
                r3 = y.getOpaque()
                VarHandle.fullFence()
                r4 = x.getOpaque()
            }
            writer0.join()
            writer1.join()
            reader0.join()
            reader1.join()
            listOf(r0, r1, r2, r3, r4)
        }
    }

    @Test
    fun testFullFencesWithConcurrentPlainAndAtomicWrites() {
        val outcomes = (1..3).flatMap { first -> (1..3).map { second -> first to second } }.toSet()
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            val t0 = thread {
                x.setPlain(1)
                VarHandle.fullFence()
                r0 = x.getOpaque()
            }
            val t1 = thread {
                x.setPlain(2)
                VarHandle.fullFence()
                r1 = x.getOpaque()
            }
            val t2 = thread { x.setOpaque(3) }
            t0.join()
            t1.join()
            t2.join()
            r0 to r1
        }
    }

    @Test
    fun testRepeatedFullFencesWithOpaqueWrites() {
        checkRepeatedFullFences(plainWrites = false)
    }

    @Test
    fun testRepeatedFullFencesWithPlainWrites() {
        checkRepeatedFullFences(plainWrites = true)
    }

    private fun checkRepeatedFullFences(plainWrites: Boolean) {
        litmusTest(assertNever(setOf(0 to 0)), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = -1
            var r1 = -1
            val t0 = thread {
                repeat(8) {
                    if (plainWrites) x.setPlain(1) else x.setOpaque(1)
                    VarHandle.fullFence()
                }
                r0 = y.getOpaque()
            }
            val t1 = thread {
                repeat(8) {
                    if (plainWrites) y.setPlain(1) else y.setOpaque(1)
                    VarHandle.fullFence()
                }
                r1 = x.getOpaque()
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }
}
