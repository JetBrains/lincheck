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

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmus

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertNever
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSame
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSometimes
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Test
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** JCStress samples imported through jMT, grouped by upstream identifiers and access-mode variants. */
class JcstressSampleMemoryModelTests {

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_advanced_02_multi_copy_atomic_fenced() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t2_r1 = 0
            var t2_r2 = 0
            var t3_r3 = 0
            var t3_r4 = 0
            val t0 = thread {
                VarHandle.fullFence()
                x.setPlain(1)
            }
            val t1 = thread {
                VarHandle.fullFence()
                y.setPlain(1)
            }
            val t2 = thread {
                t2_r1 = x.getPlain()
                VarHandle.acquireFence()
                t2_r2 = y.getPlain()
                VarHandle.acquireFence()
            }
            val t3 = thread {
                t3_r3 = y.getPlain()
                VarHandle.acquireFence()
                t3_r4 = x.getPlain()
                VarHandle.acquireFence()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(t2_r1, t2_r2, t3_r3, t3_r4)
        }
    }

    // RESULT: Never
    // eco_psc includes rb ; rf edges between plain reads across full fences.
    @Test
    fun Test_jcstress_advanced_02_multi_copy_atomic_fully_fenced() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t2_r1 = 0
            var t2_r2 = 0
            var t3_r3 = 0
            var t3_r4 = 0
            val t0 = thread {
                VarHandle.fullFence()
                x.setPlain(1)
            }
            val t1 = thread {
                VarHandle.fullFence()
                y.setPlain(1)
            }
            val t2 = thread {
                VarHandle.fullFence()
                t2_r1 = x.getPlain()
                VarHandle.fullFence()
                t2_r2 = y.getPlain()
                VarHandle.acquireFence()
            }
            val t3 = thread {
                VarHandle.fullFence()
                t3_r3 = y.getPlain()
                VarHandle.fullFence()
                t3_r4 = x.getPlain()
                VarHandle.acquireFence()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(t2_r1, t2_r2, t3_r3, t3_r4)
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_advanced_02_multi_copy_atomic_opaque() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t2_r1 = 0
            var t2_r2 = 0
            var t3_r3 = 0
            var t3_r4 = 0
            val t0 = thread {
                x.setOpaque(1)
            }
            val t1 = thread {
                y.setOpaque(1)
            }
            val t2 = thread {
                t2_r1 = x.getOpaque()
                t2_r2 = y.getOpaque()
            }
            val t3 = thread {
                t3_r3 = y.getOpaque()
                t3_r4 = x.getOpaque()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(t2_r1, t2_r2, t3_r3, t3_r4)
        }
    }

    // RESULT: Never
    @Test
    fun Test_jcstress_advanced_03_non_mca_coherence() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 2, 2, 1), listOf(2, 1, 1, 2))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            var t2_r1 = 0
            var t2_r2 = 0
            var t3_r3 = 0
            var t3_r4 = 0
            val t0 = thread {
                x.setOpaque(1)
            }
            val t1 = thread {
                x.setOpaque(2)
            }
            val t2 = thread {
                t2_r1 = x.getOpaque()
                t2_r2 = x.getOpaque()
            }
            val t3 = thread {
                t3_r3 = x.getOpaque()
                t3_r4 = x.getOpaque()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(t2_r1, t2_r2, t3_r3, t3_r4)
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_advanced_15_volatiles_are_not_fences() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 0, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val b = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            var t1_r3 = 0
            val t0 = thread {
                x.setPlain(1)
                b.set(1)
                y.setPlain(1)
            }
            val t1 = thread {
                t1_r1 = y.getPlain()
                t1_r2 = b.get()
                t1_r3 = x.getPlain()
            }
            t0.join()
            t1.join()
            Triple(t1_r1, t1_r2, t1_r3)
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_basics_05_coherence_same_read_opaque() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.RelaxedJava) {
            val a = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                a.setOpaque(1)
            }
            val t1 = thread {
                t1_r1 = a.getOpaque()
                t1_r2 = a.getOpaque()
            }
            t0.join()
            t1.join()
            t1_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_basics_05_coherence_same_read_plain() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val a = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                a.setPlain(1)
            }
            val t1 = thread {
                t1_r1 = a.getPlain()
                t1_r2 = a.getPlain()
            }
            t0.join()
            t1.join()
            t1_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_basics_05_coherence_same_read_volatile() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.RelaxedJava) {
            val a = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                a.set(1)
            }
            val t1 = thread {
                t1_r1 = a.get()
                t1_r2 = a.get()
            }
            t0.join()
            t1.join()
            t1_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_basics_06_causality_message_passing_acqrel() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setPlain(1)
                y.setRelease(1)
            }
            val t1 = thread {
                t1_r1 = y.getAcquire()
                t1_r2 = x.getPlain()
            }
            t0.join()
            t1.join()
            t1_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_basics_06_causality_message_passing_opaque() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setOpaque(1)
                y.setOpaque(1)
            }
            val t1 = thread {
                t1_r1 = y.getOpaque()
                t1_r2 = x.getOpaque()
            }
            t0.join()
            t1.join()
            t1_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_basics_06_causality_message_passing_plain() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setPlain(1)
                y.setPlain(1)
            }
            val t1 = thread {
                t1_r1 = y.getPlain()
                t1_r2 = x.getPlain()
            }
            t0.join()
            t1.join()
            t1_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_basics_06_causality_message_passing_volatile() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.set(1)
                y.set(1)
            }
            val t1 = thread {
                t1_r1 = y.get()
                t1_r2 = x.get()
            }
            t0.join()
            t1.join()
            t1_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_basics_07_consensus_dekker_acqrel() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setRelease(1)
                t0_r1 = y.getAcquire()
            }
            val t1 = thread {
                y.setRelease(1)
                t1_r2 = x.getAcquire()
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_basics_07_consensus_dekker_plain() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setPlain(1)
                t0_r1 = y.getPlain()
            }
            val t1 = thread {
                y.setPlain(1)
                t1_r2 = x.getPlain()
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Never
    @Test
    fun Test_jcstress_basics_07_consensus_dekker_volatile() {
        val outcomes: Set<Pair<Int, Int>> = setOf(1 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(outcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.set(1)
                t0_r1 = y.get()
            }
            val t1 = thread {
                y.set(1)
                t1_r2 = x.get()
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_rmw_09_gas_effects_1_cts_cts() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setPlain(1)
                var t = y.get()
                if (t == 1) {
                    y.set(0)
                }
                t0_r1 = t
            }
            val t1 = thread {
                var t = y.get()
                if (t == 0) {
                    y.set(1)
                }
                t1_r2 = x.getPlain()
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_rmw_09_gas_effects_2_cas_cas() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setPlain(1)
                t0_r1 = if (y.compareAndSet(1, 0)) 1 else 0
            }
            val t1 = thread {
                var r3 = y.compareAndSet(0, 1)
                t1_r2 = x.getPlain()
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jcstress_rmw_09_gas_effects_3_gts_cas() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setPlain(1)
                var t = y.get()
                y.set(0)
                t0_r1 = t
            }
            val t1 = thread {
                var r3 = y.compareAndSet(0, 1)
                t1_r2 = x.getPlain()
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Never
    @Test
    fun Test_jcstress_rmw_09_gas_effects_4_gas_cas() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setPlain(1)
                t0_r1 = y.getAndSet(0)
            }
            val t1 = thread {
                var r3 = y.compareAndSet(0, 1)
                t1_r2 = x.getPlain()
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }
}
