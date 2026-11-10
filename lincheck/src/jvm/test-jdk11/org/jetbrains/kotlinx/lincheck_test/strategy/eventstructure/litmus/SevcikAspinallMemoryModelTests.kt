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
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Ševčík/Aspinall transformation examples imported through jMT; method names retain the source/target labels. */
class SevcikAspinallMemoryModelTests {

    // RESULT: Never
    // forbidden per SA08 Section 4.1
    @Test
    fun Test_SA08_irrelevant_read_introduction_source() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                t0_r1 = z.getPlain()
                if (t0_r1 == 0) {
                    var r3 = x.getPlain()
                    if (r3 == 1) {
                        y.setPlain(1)
                    }
                } else {
                    var r5 = 1
                    y.setPlain(t0_r1)
                }
            }
            val t1 = thread {
                x.setPlain(1)
                t1_r2 = y.getPlain()
                z.setPlain(t1_r2)
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Never
    // forbidden per SA08 Section 4.1
    @Test
    fun Test_SA08_redundant_read_after_read_elimination_source() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r2 = 0
            val t0 = thread {
                var r1 = x.getPlain()
                y.setPlain(r1)
            }
            val t1 = thread {
                t1_r2 = y.getPlain()
                if (t1_r2 == 1) {
                    var r3 = y.getPlain()
                    x.setPlain(r3)
                } else {
                    x.setPlain(1)
                }
            }
            t0.join()
            t1.join()
            t1_r2
        }
    }

    // RESULT: Never
    // forbidden per S08 Figure 1
    @Test
    fun Test_Sevcik_2008_Tester_source() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var t1_ans = 0
            val t0 = thread {
                var r1 = x.getPlain()
                y.setPlain(r1)
                var ans = r1
            }
            val t1 = thread {
                var r1 = z.get()
                var r2 = y.getPlain()
                if (r2 == 1) {
                    var r3 = y.getPlain()
                    x.setPlain(r3)
                } else {
                    x.setPlain(1)
                }
                t1_ans = r2
            }
            t0.join()
            t1.join()
            t1_ans
        }
    }
}
