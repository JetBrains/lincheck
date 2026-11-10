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
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSometimes
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Manson thesis figures, retaining figure numbers and comments linking equivalent causality catalogue cases. */
class MansonThesisMemoryModelTests {

    // RESULT: Sometimes
    @Test
    fun Test_jmanson_thesis_fig_2_2() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(2 to 1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r2 = 0
            var t1_r1 = 0
            val t0 = thread {
                y.setPlain(1)
                t0_r2 = x.getPlain()
            }
            val t1 = thread {
                x.setPlain(2)
                t1_r1 = y.getPlain()
            }
            t0.join()
            t1.join()
            t0_r2 to t1_r1
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jmanson_thesis_fig_2_5() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 0, 1))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            var t1_r3 = 0
            val t0 = thread {
                a.setPlain(1)
                b.setPlain(1)
            }
            val t1 = thread {
                t1_r1 = b.getPlain()
                t1_r2 = a.getPlain()
                t1_r3 = a.getPlain()
            }
            t0.join()
            t1.join()
            Triple(t1_r1, t1_r2, t1_r3)
        }
    }

    // RESULT: Never
    @Test
    fun Test_jmanson_thesis_fig_3_1() {
        // exists (0:r1!=0 \/ 1:r2!=0) -- disjunction with !=
        // Since x,y start at 0 and writes are conditional on non-zero reads,
        // the only possible outcome is (0,0). We forbid any (nonzero /\ nonzero).
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                t0_r1 = x.getPlain()
                if (t0_r1 != 0) {
                    y.setPlain(42)
                }
            }
            val t1 = thread {
                t1_r2 = y.getPlain()
                if (t1_r2 != 0) {
                    x.setPlain(42)
                }
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun Test_jmanson_thesis_fig_3_3b() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(2, 2, 2))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            var t0_r1 = 0
            var t0_r2 = 0
            var t1_r3 = 0
            val t0 = thread {
                b.setPlain(2)
                t0_r1 = a.getPlain()
                t0_r2 = t0_r1
            }
            val t1 = thread {
                t1_r3 = b.getPlain()
                a.setPlain(t1_r3)
            }
            t0.join()
            t1.join()
            Triple(t0_r1, t0_r2, t1_r3)
        }
    }

    // RESULT: Never
    @Test
    fun Test_jmanson_thesis_fig_3_9() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 2, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val v1 = AtomicInteger(0)
            val v2 = AtomicInteger(0)
            var t2_r1 = 0
            var t2_r2 = 0
            var t3_r3 = 0
            var t3_r4 = 0
            val t0 = thread {
                v1.set(1)
            }
            val t1 = thread {
                v2.set(2)
            }
            val t2 = thread {
                t2_r1 = v1.get()
                t2_r2 = v2.get()
            }
            val t3 = thread {
                t3_r3 = v2.get()
                t3_r4 = v1.get()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(t2_r1, t2_r2, t3_r3, t3_r4)
        }
    }

    // RESULT: Never
    // repeat of CausalityTestCases-Test05 modulo renaming and different constant.
    @Test
    fun Test_jmanson_thesis_fig_4_5() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0, 42, 42))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            var t3_r0 = 0
            val t0 = thread {
                t0_r1 = x.getPlain()
                y.setPlain(t0_r1)
            }
            val t1 = thread {
                t1_r2 = y.getPlain()
                x.setPlain(t1_r2)
            }
            val t2 = thread {
                z.setPlain(42)
            }
            val t3 = thread {
                t3_r0 = z.getPlain()
                x.setPlain(t3_r0)
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            Triple(t3_r0, t0_r1, t1_r2)
        }
    }

    // RESULT: Never
    // repeat of CausalityTestCases-Test10 modulo renaming and different constant.
    @Test
    fun Test_jmanson_thesis_fig_4_6() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0, 42, 42))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            var t3_r0 = 0
            val t0 = thread {
                t0_r1 = x.getPlain()
                if (t0_r1 == 1) {
                    y.setPlain(1)
                }
            }
            val t1 = thread {
                t1_r2 = y.getPlain()
                if (t1_r2 == 1) {
                    x.setPlain(1)
                }
            }
            val t2 = thread {
                z.setPlain(1)
            }
            val t3 = thread {
                t3_r0 = z.getPlain()
                if (t3_r0 == 1) {
                    x.setPlain(42)
                }
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            Triple(t3_r0, t0_r1, t1_r2)
        }
    }

    // RESULT: Never
    // repeat of CausalityTestCases-Test12, modulo replacing array by variables.
    @Test
    fun Test_jmanson_thesis_fig_4_8() {
        // exists (0:aoobe!=1 /\ 0:r1=1 /\ 0:r2=1 /\ 1:r3=1)
        // aoobe is only set to 1 when r1 >= 2, so aoobe!=1 is implied by r1==1.
        // We approximate the forbidden outcome as (1, 1, 1) for (r1, r2, r3).
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val a0 = AtomicInteger(1)
            val a1 = AtomicInteger(2)
            var t0_r1 = 0
            var t0_r2 = 0
            var t1_r3 = 0
            val t0 = thread {
                t0_r1 = x.getPlain()
                if (t0_r1 == 0) {
                    a0.setPlain(0)
                } else {
                    if (t0_r1 == 1) {
                        a1.setPlain(0)
                    } else {
                        var aoobe = 1
                    }
                }
                t0_r2 = a0.getPlain()
                y.setPlain(t0_r2)
            }
            val t1 = thread {
                t1_r3 = y.getPlain()
                x.setPlain(t1_r3)
            }
            t0.join()
            t1.join()
            Triple(t0_r1, t0_r2, t1_r3)
        }
    }

    // RESULT: Never
    @Test
    fun Test_jmanson_thesis_fig_4_9() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            val c = AtomicInteger(0)
            val d = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            var t2_r3 = 0
            var t3_r4 = 0
            val t0 = thread {
                t0_r1 = a.getPlain()
                if (t0_r1 == 0) {
                    b.setPlain(1)
                }
            }
            val t1 = thread {
                t1_r2 = b.getPlain()
                if (t1_r2 == 1) {
                    c.setPlain(1)
                }
            }
            val t2 = thread {
                t2_r3 = c.getPlain()
                if (t2_r3 == 1) {
                    d.setPlain(1)
                }
            }
            val t3 = thread {
                t3_r4 = d.getPlain()
                if (t3_r4 == 1) {
                    c.setPlain(1)
                    a.setPlain(1)
                }
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(t0_r1, t1_r2, t2_r3, t3_r4)
        }
    }

    // RESULT: Never
    @Test
    fun Test_jmanson_thesis_fig_4_11() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            var t2_r3 = 0
            val t0 = thread {
                t0_r1 = x.getPlain()
                if (t0_r1 == 0) {
                    x.setPlain(1)
                }
            }
            val t1 = thread {
                t1_r2 = x.getPlain()
                y.setPlain(t1_r2)
            }
            val t2 = thread {
                t2_r3 = y.getPlain()
                x.setPlain(t2_r3)
            }
            t0.join()
            t1.join()
            t2.join()
            Triple(t0_r1, t1_r2, t2_r3)
        }
    }
}
