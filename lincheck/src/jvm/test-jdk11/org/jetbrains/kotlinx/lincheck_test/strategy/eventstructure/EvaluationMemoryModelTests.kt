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

import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.lincheck.datastructures.scenario
import org.jetbrains.lincheck.util.JdkVersion
import org.jetbrains.lincheck.util.isJdk8
import org.jetbrains.lincheck.util.jdkVersion
import org.junit.Assume.assumeFalse
import org.junit.Before
import java.util.concurrent.atomic.*
import org.junit.Test
import org.junit.Ignore
import java.lang.invoke.VarHandle
import kotlin.concurrent.thread

class EvaluationMemoryModelTests {

    // =============================== JAM19 ==============================
    @Test
    fun testSBOpaque() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0), (0 to 1), (1 to 1), (1 to 0))
        litmusTest(assertSame(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                x.setRelease(1)
                r0 = y.getOpaque()
            }
            val t1 = thread {
                y.setOpaque(1)
                r1 = x.getOpaque()
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    @Test
    fun test4SB() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(0,0,0,0))
        litmusTest(assertSometimes(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            val a = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            var r2 = 0;
            var r3 = 0;
            val t0 = thread {
                x.setOpaque(1)
                r0 = y.getOpaque()
            }
            val t1 = thread {
                y.setOpaque(1)
                r1 = z.getOpaque()
            }
            val t2 = thread {
                z.setOpaque(1)
                r2 = a.getOpaque()
            }
            val t3 = thread {
                a.setOpaque(1)
                r3 = x.getOpaque()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(r0, r1, r2, r3)
        }
    }


    @Test
    fun test6SB() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(0,0,0,0,0,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            val c = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            var r2 = 0;
            var r3 = 0;
            var r4 = 0;
            var r5 = 0;
            val t0 = thread {
                x.setOpaque(1)
                r0 = y.getOpaque()
            }
            val t1 = thread {
                y.setOpaque(1)
                r1 = z.getOpaque()
            }
            val t2 = thread {
                z.setOpaque(1)
                r2 = a.getOpaque()
            }
            val t3 = thread {
                a.setOpaque(1)
                r3 = b.getOpaque()
            }
            val t4 = thread {
                b.setOpaque(1)
                r4 = c.getOpaque()
            }
            val t5 = thread {
                c.setOpaque(1)
                r5 = x.getOpaque()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            t4.join()
            t5.join()
            listOf(r0, r1, r2, r3, r4, r5)
        }
    }

    @Test
    fun testArfna() {
        // x=1 /\ y=1, should never happen.
        val forbiddentOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddentOutcomes), MemoryModel.JAM21) {
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                r0 = x.getOpaque()
                if (r0 != 0) {
                    val t = a.getPlain()
                    b.setPlain(1)
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
            }
            val t1 = thread {
                r1 = y.getOpaque()
                if (r1 != 0) {
                    if (b.getPlain() != 0) {
                        a.setPlain(1)
                        x.setOpaque(1)
                    }
                }
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    @Test
    fun testArfnaTransformed() {
        // x=1 /\ y=1, should never happen.
        val forbiddentOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddentOutcomes), MemoryModel.JAM21) {
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                r0 = x.getOpaque()
                if (r0 != 0) {
                    b.setPlain(1)
                    val t = a.getPlain()
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
            }
            val t1 = thread {
                r1 = y.getOpaque()
                if (r1 != 0) {
                    if (b.getPlain() != 0) {
                        a.setPlain(1)
                        x.setOpaque(1)
                    }
                }
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    @Test
    fun testB() {
        //NOTE: This is just load buffering, I am not sure why the name is like that.
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                r0 = x.getOpaque()
                y.setOpaque(1)
            }
            val t1 = thread {
                r1 = y.getOpaque()
                x.setOpaque(1)
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    @Test
    fun testBReorder() {
        val allowedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertSometimes(allowedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                y.setOpaque(1)
                r0 = x.getOpaque()
            }
            val t1 = thread {
                r1 = y.getOpaque()
                x.setOpaque(1)
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    @Test
    fun testC() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                r0 = x.getOpaque()
                if (r0 != 0) {
                    val t = p.getPlain()
                    q.setPlain(1)
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
            }
            val t1 = thread {
                r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
            }
            t0.join()
            t1.join()
            p.get() to q.get()
        }
    }

    @Test
    fun testCReorder() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                r0 = x.getOpaque()
                if (r0 != 0) {
                    q.setPlain(1)
                    val t = p.getPlain()
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
            }
            val t1 = thread {
                r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
            }
            t0.join()
            t1.join()
            p.get() to q.get()
        }
    }

    @Test
    fun testCoRWR() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val eax = x.getOpaque()
            x.setOpaque(1)
            val ebx = x.getOpaque()
            eax to ebx
        }
    }


    @Test
    fun testCyc() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                r0 = x.getOpaque()
                if (r0 != 0) y.setOpaque(1)
            }
            val t1 = thread {
                r1 = y.getOpaque()
                if (r1 != 0) x.setOpaque(1)
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    @Test
    fun testFig1() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1,1,1))
        litmusTest(assertSame(expectedOutcomes, UNKNOWN), MemoryModel.JAM21) {
            val a = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val t0 = thread {
                a.setPlain(1)
                x.getOpaque()
                a.getPlain()
                y.setOpaque(1)
            }
            val t1 = thread {
                y.getOpaque()
                x.setOpaque(1)
            }
            t0.join()
            t1.join()
            Triple(a.get(), x.get(), y.get())
        }
    }

    @Test
    fun testIriwInternal() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0a = 0;
            var t0b = 0;
            var t1a = 0;
            var t1b = 0;
            val t0 = thread {
                x.setOpaque(1)
                t0a = x.getOpaque()
                t0b = y.getOpaque()
            }
            val t1 = thread {
                y.setOpaque(1)
                t1a = y.getOpaque()
                t1b = x.getOpaque()
            }
            t0.join()
            t1.join()
            listOf(t0a, t0b, t1a, t1b)
        }
    }

    @Test
    fun testMpRelaxed() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = -1;
            val t0 = thread {
                x.setPlain(1)
                y.setOpaque(1)
            }
            val t1 = thread {
                r0 = y.getOpaque()
                if (r0 != 0) r1 = x.getPlain()
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }


    @Test
    fun testPodrw001() {
        // NOTE: this is just Store Buffering with 3 reads
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0,0,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            var r2 = 0;
            val t0 = thread {
                z.setOpaque(1)
                r0 = x.getOpaque()
            }
            val t1 = thread {
                x.setOpaque(1)
                r1 = y.getOpaque()
            }
            val t2 = thread {
                y.setOpaque(1)
                r2 = z.getOpaque()
            }
            t0.join()
            t1.join()
            t2.join()
            Triple(r0, r1, r2)
        }
    }

    @Test
    fun testX001() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0,1,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var eax = 0;
            var ebx = 0;
            val t0 = thread {
                x.setOpaque(1)
                r0 = y.getOpaque()
            }
            val t1 = thread {
                y.setOpaque(1)
                eax = y.getOpaque()
                ebx = x.getOpaque()
            }
            t0.join()
            t1.join()
            Triple(r0, eax, ebx)
        }
    }


    @Test
    fun testX003() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(2,2,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var eax = 0;
            var ebx = 0;
            val t0 = thread {
                x.setOpaque(1)
                y.setOpaque(1)
            }
            val t1 = thread {
                y.setOpaque(2)
                eax = y.getOpaque()
                ebx = x.getOpaque()
            }
            t0.join()
            t1.join()
            Triple(y.get(), eax, ebx)
        }
    }

    @Test
    fun testX006() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((2 to 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            val t0 = thread {
                x.setOpaque(1)
                y.setOpaque(1)
            }
            val t1 = thread {
                y.setOpaque(2)
                r0 = x.getOpaque()
            }
            t0.join()
            t1.join()
            y.get() to r0
        }
    }

    @Test
    fun testX86_2plus2W() {
        val expectedOutcomes: Set<Pair<Int,Int>> = setOf((2 to 2))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val t0 = thread {
                x.setOpaque(2)
                y.setOpaque(1)
            }
            val t1 = thread {
                y.setOpaque(2)
                x.setOpaque(1)
            }
            t0.join()
            t1.join()
            x.get() to y.get()
        }
    }


    @Test
    fun testA1() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0;
            val t0 = thread {
                y.getOpaque()
                x.setRelease(1)
            }
            val t1 = thread {
                r1 = x.getAcquire()
                if (r1 != 0) {
                    y.setPlain(1)
                }
            }
            t0.join()
            t1.join()
            x.get() to y.get()
        }
    }

    @Test
    fun testA1Reorder() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0;
            val t0 = thread {
                x.setRelease(1)
                y.getOpaque()
            }
            val t1 = thread {
                r1 = x.getAcquire()
                if (r1 != 0) {
                    y.setPlain(1)
                }
            }
            t0.join()
            t1.join()
            x.get() to y.get()
        }
    }

    @Test
    fun testA3() {
        val expectedOutcomes: Set<Int> = setOf(1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            val t0 = thread {
                y.setPlain(1)
                x.setRelease(1)
            }
            val t1 = thread {
                r1 = x.getAcquire()
                if (r1 != 0) {
                    y.getOpaque()
                }
            }
            t0.join()
            t1.join()
            r1
        }
    }

    @Test
    fun testA3Reorder() {
        val expectedOutcomes: Set<Int> = setOf(1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            val t0 = thread {
                y.setPlain(1)
                x.setRelease(1)
            }
            val t1 = thread {
                y.getOpaque()
                r1 = x.getAcquire()
            }
            t0.join()
            t1.join()
            r1
        }
    }

    @Test
    fun testIRIWPoaasLL() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0;
            var r2 = 0;
            var r3 = 0;
            var r4 = 0;
            val t0 = thread {
                x.setRelease(1)
            }
            val t1 = thread {
                y.setRelease(1)
            }
            val t2 = thread {
                r1 = x.getAcquire()
                r2 = y.getAcquire()
            }
            val t3 = thread {
                r3 = y.getAcquire()
                r4 = x.getAcquire()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(r1, r2, r3, r4)
        }
    }

    @Test
    fun testIRIWPoapsLL() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0;
            var r2 = 0;
            var r3 = 0;
            var r4 = 0;
            val t0 = thread {
                x.setRelease(1)
            }
            val t1 = thread {
                y.setRelease(1)
            }
            val t2 = thread {
                r1 = x.getAcquire()
                r2 = y.getOpaque()
            }
            val t3 = thread {
                r3 = y.getAcquire()
                r4 = x.getOpaque()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(r1, r2, r3, r4)
        }
    }


    @Test
    fun testLinearisation() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,1,1,1,1))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val w = AtomicInteger(0)
            val z = AtomicInteger(0)
            var t = 0;
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                t = x.getAcquire() + y.getPlain()
                if (t == 2) {
                    w.setRelease(1)
                }
            }
            val t1 = thread {
                r0 = w.getOpaque()
                if (r0 != 0) {
                    z.setOpaque(1)
                }
            }
            val t2 = thread {
                r1 = z.getOpaque()
                if (r1 != 0) {
                    y.setPlain(1)
                    x.setRelease(1)
                }
            }
            t0.join()
            t1.join()
            t2.join()
            listOf(t, w.get(), x.get(), y.get(), z.get())
        }
    }

    @Test
    fun testLinearisation2() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,1,1,1,1))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val w = AtomicInteger(0)
            val z = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                val tt = x.getAcquire()
                r0 = tt + y.getPlain()
                if (r0 == 2) {
                    w.setRelease(1)
                }
            }
            val t1 = thread {
                r0 = w.getOpaque()
                if (r0 != 0) {
                    z.setOpaque(1)
                }
            }
            val t2 = thread {
                r1 = z.getOpaque()
                if (r1 != 0) {
                    y.setPlain(1)
                    x.setRelease(1)
                }
            }
            t0.join()
            t1.join()
            t2.join()
            listOf(r0, w.get(), x.get(), y.get(), z.get())
        }
    }

    @Test
    fun testMpRelacq() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = -1;
            val t0 = thread {
                x.setPlain(1)
                y.setRelease(1)
            }
            val t1 = thread {
                r0 = y.getAcquire()
                if (r0 == 1) r1 = x.getPlain()
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    // NOTE: this test is interesting because C11 forbids this behavior but JAM allows it
    @Test
    fun testMpRelacqRs() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((2 to 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = -1;
            val t0 = thread {
                x.setPlain(1)
                y.setRelease(1)
                y.setOpaque(2)
            }
            val t1 = thread {
                r0 = y.getAcquire()
                if (r0 == 2) {
                    r1 = x.getPlain()
                }
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    @Test
    fun testRoachmotel() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,1,1,1))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.JAM21) {
            val a = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var r0 = 0;
            var r2 = 0;
            var r3 = 0;
            val t0 = thread {
                z.setRelease(1)
                a.setPlain(1)
            }
            val t1 = thread {
                r0 = x.getOpaque()
                if (r0 != 0) {
                    z.getAcquire()
                    r2 = a.getPlain()
                    if (r2 != 0) {
                        y.setOpaque(1)
                    }
                }
            }
            val t2 = thread {
                r3 = y.getOpaque()
                if (r3 != 0)  {
                    x.setOpaque(1)
                }
            }
            t0.join()
            t1.join()
            t2.join()
            listOf(a.get(), z.get(), x.get(), y.get())
        }
    }

    @Test
    fun testRoachmotel2() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,1,1,1))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.JAM21) {
            val a = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var r0 = 0;
            var r2 = 0;
            var r3 = 0;
            val t0 = thread {
                a.setPlain(1)
                z.setRelease(1)
            }
            val t1 = thread {
                r0 = x.getOpaque()
                if (r0 != 0) {
                    z.getAcquire()
                    r2 = a.getPlain()
                    if (r2 != 0) {
                        y.setOpaque(1)
                    }
                }
            }
            val t2 = thread {
                r3 = y.getOpaque()
                if (r3 != 0) {
                    x.setOpaque(1)
                }
            }
            t0.join()
            t1.join()
            t2.join()
            listOf(a.get(), z.get(), x.get(), y.get())
        }
    }

    @Test
    fun testRseqWeak() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((3 to 1))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            val t0 = thread {
                x.setOpaque(2)
            }
            val t1 = thread {
                y.setPlain(1)
                x.setRelease(1)
                x.setOpaque(3)
            }
            val t2 = thread {
                r0 = x.getAcquire()
                if (r0 == 3) {
                    y.getPlain()
                }
            }
            t0.join()
            t1.join()
            t2.join()
            x.get() to y.get()
        }
    }

    @Test
    fun testRseqWeak2() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((3 to 1))
        litmusTest(assertSame(expectedOutcomes, UNKNOWN), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            val t0 = thread {
                y.setPlain(1)
                x.setRelease(1)
                x.setOpaque(3)
            }
            val t1 = thread {
                r0 = x.getAcquire()
                if (r0 == 3) {
                    y.getPlain()
                }
            }
            t0.join()
            t1.join()
            x.get() to y.get()
        }
    }

    @Test
    fun testTotalco() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1,1,1))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0r = 0;
            var t1r = 0;
            var t2r = 0;
            val t0 = thread {
                t0r = x.getOpaque()
                x.setOpaque(1)
            }
            val t1 = thread {
                t1r = y.getAcquire()
                x.setOpaque(2)
            }
            val t2 = thread {
                t2r = x.getAcquire()
                y.setOpaque(1)
            }
            t0.join()
            t1.join()
            t2.join()
            Triple(t0r, t1r, t2r)
        }
    }

    @Test
    fun testWWRRWWRRWsilpPoaaWsilpPoaa() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,2,2,0,2,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1a = 0;
            var t1b = 0;
            var t3a = 0;
            var t3b = 0;
            val t0 = thread {
                x.setRelease(1)
                x.setRelease(2)
            }
            val t1 = thread {
                t1a = x.getAcquire()
                t1b = y.getAcquire()
            }
            val t2 = thread {
                y.setRelease(1)
                y.setRelease(2)
            }
            val t3 = thread {
                t3a = y.getAcquire()
                t3b = x.getAcquire()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(x.get(), y.get(), t1a, t1b, t3a, t3b)
        }
    }

    @Test
    fun testMpFences() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)

            var r0 = -1
            var r1 = -1

            val t0 = thread {
                x.setPlain(1)
                VarHandle.releaseFence()
                y.setOpaque(1)
            }

            val t1 = thread {
                r0 = y.getOpaque()
                VarHandle.acquireFence()
                if (r0 == 1) {
                    r1 = x.getPlain()
                }
            }

            t0.join()
            t1.join()
            r0 to r1
        }
    }


    @Test
    fun testFig6() {
        class TestFig6 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
                x.set(2)
                y.set(1)
            }
            fun thread1() {
                x.setOpaque(3)
                y.set(2)
            }
            fun thread2(): Int {
                y.set(3)
                return x.get()
            }
            fun thread3(): List<Int> {
                val s1 = x.getOpaque()
                val s2 = x.getOpaque()
                val s3 = x.getOpaque()
                val t1 = y.getOpaque()
                val t2 = y.getOpaque()
                val t3 = y.getOpaque()
                return listOf(s1, s2, s3, t1, t2, t3)
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestFig6::thread0) }
                thread { actor(TestFig6::thread1) }
                thread { actor(TestFig6::thread2) }
                thread { actor(TestFig6::thread3) }
            }
        }
        // RC11 allows this, but C11 does not
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 1, 1, 2, 2, 3, 3))
        litmusTest(TestFig6::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.JAM21, 10_000) { results ->
            val r = getValue<Int>(results.parallelResults[2][0]!!)
            val t3 = getValue<List<Int>>(results.parallelResults[3][0]!!)
            listOf(r, t3[0], t3[1], t3[2], t3[3], t3[4], t3[5])
        }
    }

    @Test
    fun testFig6Translated() {
        class TestFig6Translated {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
                x.set(2)
                y.set(1)
            }
            fun thread1() {
                x.set(3)
                y.set(2)
            }
            fun thread2(): Int {
                y.set(3)
                return x.get()
            }
            fun thread3(): List<Int> {
                val s1 = x.getOpaque()
                val s2 = x.getOpaque()
                val s3 = x.getOpaque()
                val t1 = y.getOpaque()
                val t2 = y.getOpaque()
                val t3 = y.getOpaque()
                return listOf(s1, s2, s3, t1, t2, t3)
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestFig6Translated::thread0) }
                thread { actor(TestFig6Translated::thread1) }
                thread { actor(TestFig6Translated::thread2) }
                thread { actor(TestFig6Translated::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 1, 1, 2, 2, 3, 3))
        litmusTest(TestFig6Translated::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.JAM21, 10_000) { results ->
            val r = getValue<Int>(results.parallelResults[2][0]!!)
            val t3 = getValue<List<Int>>(results.parallelResults[3][0]!!)
            listOf(r, t3[0], t3[1], t3[2], t3[3], t3[4], t3[5])
        }
    }

    @Test
    fun testIriwAcqSc() {
        class TestIriwAcqSc {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(1)
            }
            fun thread1() {
                y.set(1)
            }
            fun thread2(): Pair<Int, Int> {
                val r1 = x.getAcquire()
                val r2 = y.get()
                return r1 to r2
            }
            fun thread3(): Pair<Int, Int> {
                val r3 = y.getAcquire()
                val r4 = x.get()
                return r3 to r4
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestIriwAcqSc::thread0) }
                thread { actor(TestIriwAcqSc::thread1) }
                thread { actor(TestIriwAcqSc::thread2) }
                thread { actor(TestIriwAcqSc::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(TestIriwAcqSc::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.JAM21) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun testIriwScRlxAcq() {
        class TestIriwScRlxAcq {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(1)
                x.setOpaque(2)
            }
            fun thread1() {
                y.set(1)
                y.setOpaque(2)
            }
            fun thread2(): Pair<Int, Int> {
                val r1 = x.getAcquire()
                val r2 = y.getAcquire()
                return r1 to r2
            }
            fun thread3(): Pair<Int, Int> {
                val r3 = y.getAcquire()
                val r4 = x.getAcquire()
                return r3 to r4
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestIriwScRlxAcq::thread0) }
                thread { actor(TestIriwScRlxAcq::thread1) }
                thread { actor(TestIriwScRlxAcq::thread2) }
                thread { actor(TestIriwScRlxAcq::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2, 0, 2, 0))
        litmusTest(TestIriwScRlxAcq::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.JAM21) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun testIriwSc() {
        class TestIriwSc {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(1)
            }
            fun thread1() {
                y.set(1)
            }
            fun thread2(): Pair<Int, Int> {
                val r1 = x.get()
                val r2 = y.get()
                return r1 to r2
            }
            fun thread3(): Pair<Int, Int> {
                val r3 = y.get()
                val r4 = x.get()
                return r3 to r4
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestIriwSc::thread0) }
                thread { actor(TestIriwSc::thread1) }
                thread { actor(TestIriwSc::thread2) }
                thread { actor(TestIriwSc::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(TestIriwSc::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun testMpSc() {
        class TestMpSc {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setPlain(1)
                y.set(1)
            }
            fun thread1(): Pair<Int, Int> {
                val r0 = y.get()
                var r1 = -1
                if (r0 == 1) {
                    r1 = x.getPlain()
                }
                return r0 to r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestMpSc::thread0) }
                thread { actor(TestMpSc::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(TestMpSc::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testZ6U() {
        class TestZ6U {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(1)
                y.setRelease(1)
            }
            fun thread1(): Pair<Int, Int> {
                val r1 = y.getAndAdd(1)
                val r2 = y.getOpaque()
                return r1 to r2
            }
            fun thread2(): Int {
                y.set(3)
                return x.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestZ6U::thread0) }
                thread { actor(TestZ6U::thread1) }
                thread { actor(TestZ6U::thread2) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 3, 0))
        litmusTest(TestZ6U::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val t2 = getValue<Int>(results.parallelResults[2][0]!!)
            listOf(t1.first, t1.second, t2)
        }
    }

    @Test
    fun testA3v2() {
        class TestA3v2 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                y.setPlain(1)
                x.setRelease(1)
            }
            fun thread1(): Int {
                val r0 = x.compareAndExchangeAcquire(1, 2)
                var r1 = -1
                if (r0 == 1) {
                    r1 = y.getPlain()
                }
                return r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestA3v2::thread0) }
                thread { actor(TestA3v2::thread1) }
            }
        }
        val expectedOutcomes: Set<Int> = setOf(1)
        litmusTest(TestA3v2::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun testCp() {
        class TestCp {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val t = p.compareAndExchangeAcquire(1, 2)
                    q.setPlain(1)
                    if (t == 1) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCp::thread0) }
                thread { actor(TestCp::thread1) }
            }
            post { actor(TestCp::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCp::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testCpReorder() {
        class TestCpReorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    q.setPlain(1)
                    val t = p.compareAndExchangeAcquire(1, 2)
                    if (t == 1) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCpReorder::thread0) }
                thread { actor(TestCpReorder::thread1) }
            }
            post { actor(TestCpReorder::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCpReorder::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }


    @Test
    fun testCpq() {
        class TestCpq {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val t = p.compareAndExchangeAcquire(1, 2)
                    val u = q.compareAndExchangeAcquire(0, 1)
                    if (t == 1) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCpq::thread0) }
                thread { actor(TestCpq::thread1) }
            }
            post { actor(TestCpq::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCpq::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testCpqReorder() {
        class TestCpqReorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val u = q.compareAndExchangeAcquire(0, 1)
                    val t = p.compareAndExchangeAcquire(1, 2)
                    if (t == 1) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCpqReorder::thread0) }
                thread { actor(TestCpqReorder::thread1) }
            }
            post { actor(TestCpqReorder::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCpqReorder::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testCq() {
        class TestCq {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val t = p.getPlain()
                    val u = q.compareAndExchangeAcquire(0, 1)
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCq::thread0) }
                thread { actor(TestCq::thread1) }
            }
            post { actor(TestCq::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCq::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testCqReorder() {
        class TestCqReorder {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val p = AtomicInteger(0)
            val q = AtomicInteger(0)
            fun thread0(): Int {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val u = q.compareAndExchangeAcquire(0, 1)
                    val t = p.getPlain()
                    if (t != 0) {
                        y.setOpaque(1)
                    }
                }
                return r0
            }
            fun thread1(): Int {
                val r1 = y.getOpaque()
                if (r1 != 0) {
                    val r2 = q.getPlain()
                    if (r2 != 0) {
                        p.setPlain(1)
                        x.setOpaque(1)
                    }
                }
                return r1
            }
            fun post(): Pair<Int, Int> {
                return p.get() to q.get()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCqReorder::thread0) }
                thread { actor(TestCqReorder::thread1) }
            }
            post { actor(TestCqReorder::post) }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(TestCqReorder::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testX002() {
        class TestX002 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.setOpaque(1)
                VarHandle.fullFence()
                return y.getOpaque()
            }
            fun thread1(): Pair<Int, Int> {
                y.setOpaque(1)
                val eax = y.getOpaque()
                val ebx = x.getOpaque()
                return eax to ebx
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestX002::thread0) }
                thread { actor(TestX002::thread1) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(0, 1, 0))
        litmusTest(TestX002::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.JAM21) { results ->
            val eax0 = getValue<Int>(results.parallelResults[0][0]!!)
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            listOf(eax0, t1.first, t1.second)
        }
    }

    @Test
    fun testX005() {
        class TestX005 {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.setOpaque(1)
                VarHandle.fullFence()
                return y.getOpaque()
            }
            fun thread1(): Int {
                y.setOpaque(1)
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestX005::thread0) }
                thread { actor(TestX005::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0))
        litmusTest(TestX005::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.JAM21) { results ->
            val eax0 = getValue<Int>(results.parallelResults[0][0]!!)
            val eax1 = getValue<Int>(results.parallelResults[1][0]!!)
            eax0 to eax1
        }
    }


    @Test
    fun testSbPlusRfis() {
        class TestClass {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Pair<Int, Int> {
                x.setPlain(1)
                val r0 = x.get()
                val r1 = y.get()
                return r0 to r1
            }
            fun thread1(): Pair<Int, Int> {
                y.setPlain(1)
                val r0 = y.get()
                val r1 = x.get()
                return r0 to r1
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestClass::thread0) }
                thread { actor(TestClass::thread1) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        //NOTE: this behaviour is forbidden by JAM21, but actually happens in practice as mentioned in the JMT paper
        // https://arxiv.org/pdf/2604.15978, section 7, scenario 3
        litmusTest(TestClass::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.JAM21) { results ->
            val t0 = getValue<Pair<Int,Int>>(results.parallelResults[0][0]!!)
            val t1 = getValue<Pair<Int,Int>>(results.parallelResults[1][0]!!)
            listOf(t0.first, t0.second, t1.first, t1.second)
        }
    }

    @Test
    fun testSeq() {
        class TestSeq {
            val a = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                a.set(1)
            }
            fun thread1() {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val r1 = a.get()
                    if (r1 != 0) {
                        y.setOpaque(1)
                    }
                }
            }
            fun thread2() {
                val r2 = y.getOpaque()
                if (r2 != 0) {
                    x.setOpaque(1)
                }
            }
            fun post(): Triple<Int, Int, Int> {
                return Triple(a.get(), x.get(), y.get())
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestSeq::thread0) }
                thread { actor(TestSeq::thread1) }
                thread { actor(TestSeq::thread2) }
            }
            post { actor(TestSeq::post) }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 1))
        litmusTest(TestSeq::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Triple<Int, Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testSeq2() {
        class TestSeq2 {
            val a = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                a.set(1)
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    val r1 = a.get()
                    if (r1 != 0) {
                        y.setOpaque(1)
                    }
                }
            }
            fun thread1() {
                val r2 = y.getOpaque()
                if (r2 != 0) {
                    x.setOpaque(1)
                }
            }
            fun post(): Triple<Int, Int, Int> {
                return Triple(a.get(), x.get(), y.get())
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestSeq2::thread0) }
                thread { actor(TestSeq2::thread1) }
            }
            post { actor(TestSeq2::post) }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 1))
        litmusTest(TestSeq2::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Triple<Int, Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testStrengthen() {
        class TestStrengthen {
            val a = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0() {
                a.set(1)
                z.setOpaque(1)
            }
            fun thread1() {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    z.getAcquire()
                    val r2 = a.get()
                    if (r2 != 0) {
                        y.setOpaque(1)
                    }
                }
            }
            fun thread2() {
                val r3 = y.getOpaque()
                if (r3 != 0) {
                    x.setOpaque(1)
                }
            }
            fun post(): List<Int> {
                return listOf(a.get(), z.get(), x.get(), y.get())
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestStrengthen::thread0) }
                thread { actor(TestStrengthen::thread1) }
                thread { actor(TestStrengthen::thread2) }
            }
            post { actor(TestStrengthen::post) }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 1, 1, 1))
        litmusTest(TestStrengthen::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<List<Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testStrengthen2() {
        class TestStrengthen2 {
            val a = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0() {
                a.set(1)
                z.setRelease(1)
            }
            fun thread1() {
                val r0 = x.getOpaque()
                if (r0 != 0) {
                    z.getAcquire()
                    val r2 = a.get()
                    if (r2 != 0) {
                        y.setOpaque(1)
                    }
                }
            }
            fun thread2() {
                val r3 = y.getOpaque()
                if (r3 != 0) {
                    x.setOpaque(1)
                }
            }
            fun post(): List<Int> {
                return listOf(a.get(), z.get(), x.get(), y.get())
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestStrengthen2::thread0) }
                thread { actor(TestStrengthen2::thread1) }
                thread { actor(TestStrengthen2::thread2) }
            }
            post { actor(TestStrengthen2::post) }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 1, 1, 1))
        litmusTest(TestStrengthen2::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<List<Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun testCppMemIriwRelacq() {
        class TestCppMemIriwRelacq {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.set(1)
            }
            fun thread1() {
                y.set(1)
            }
            fun thread2(): Pair<Int, Int> {
                val r1 = x.getAcquire()
                val r2 = y.getAcquire()
                return r1 to r2
            }
            fun thread3(): Pair<Int, Int> {
                val r3 = y.getAcquire()
                val r4 = x.getAcquire()
                return r3 to r4
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestCppMemIriwRelacq::thread0) }
                thread { actor(TestCppMemIriwRelacq::thread1) }
                thread { actor(TestCppMemIriwRelacq::thread2) }
                thread { actor(TestCppMemIriwRelacq::thread3) }
            }
        }
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(TestCppMemIriwRelacq::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.JAM21) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun test2Plus2W() {
        class Test2Plus2WVolatile {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.set(1)
                y.set(2)
                return y.getOpaque()
            }
            fun thread1(): Int {
                y.set(1)
                x.set(2)
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(Test2Plus2WVolatile::thread0) }
                thread { actor(Test2Plus2WVolatile::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(Test2Plus2WVolatile::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            val r1_0 = getValue<Int>(results.parallelResults[0][0]!!)
            val r1_1 = getValue<Int>(results.parallelResults[1][0]!!)
            r1_0 to r1_1
        }
    }

    // ==================== sevcik ====================

    // RESULT: Never
    // forbidden per SA08 Section 4.1
    @Test
    fun test_SA08_irrelevant_read_introduction_source() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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
    fun test_SA08_redundant_read_after_read_elimination_source() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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
    fun test_Sevcik_2008_Tester_source() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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

    // Ignored:  fun test_Sevcik_2008_Tester_target() {

    // ==================== jam ====================

    // Ignored: test_LBP21_register_promotion_for_volatile_source

    // Ignored: test_LBP21_register_promotion_for_volatile_target() {

    // RESULT: Never
    // LBP21 Figure 1 / Listing L.1
    @Test
    fun test_LBP21_volatile_non_sc_4() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(0, 1, 1, 2))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t2_r2 = 0
            var t3_r3 = 0
            var t3_r4 = 0
            val t0 = thread {
                x.set(2)
                t0_r1 = y.get()
            }
            val t1 = thread {
                y.set(1)
            }
            val t2 = thread {
                t2_r2 = y.get()
                x.set(1)
            }
            val t3 = thread {
                t3_r3 = x.get()
                t3_r4 = x.get()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(t0_r1, t2_r2, t3_r3, t3_r4)
        }
    }

    // RESULT: Never
    // LBP21 Listing L.2
    @Test
    fun test_LBP21_volatile_non_sc_5() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(0, 1, 0, 1, 2))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var t0_r1 = 0
            var t2_r2 = 0
            var t3_r3 = 0
            var t4_r4 = 0
            var t4_r5 = 0
            val t0 = thread {
                x.set(1)
                t0_r1 = y.get()
            }
            val t1 = thread {
                y.set(1)
            }
            val t2 = thread {
                t2_r2 = y.get()
                z.set(1)
            }
            val t3 = thread {
                z.set(2)
                t3_r3 = x.get()
            }
            val t4 = thread {
                t4_r4 = z.get()
                t4_r5 = z.get()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            t4.join()
            listOf(t0_r1, t2_r2, t3_r3, t4_r4, t4_r5)
        }
    }


    // ==================== causality-test-cases ====================

    // RESULT: Never
    @Test
    fun test_causality_test_case_05() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            var t3_r3 = 0
            val t0 = thread {
                t0_r1 = x.getPlain()
                y.setPlain(t0_r1)
            }
            val t1 = thread {
                t1_r2 = y.getPlain()
                x.setPlain(t1_r2)
            }
            val t2 = thread {
                z.setPlain(1)
            }
            val t3 = thread {
                t3_r3 = z.getPlain()
                x.setPlain(t3_r3)
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            Triple(t0_r1, t1_r2, t3_r3)
        }
    }

    // RESULT: Never
    @Test
    fun test_causality_test_case_10() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            var t3_r3 = 0
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
                t3_r3 = z.getPlain()
                if (t3_r3 == 1) {
                    x.setPlain(1)
                }
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            Triple(t0_r1, t1_r2, t3_r3)
        }
    }


    // RESULT: Never
    @Test
    fun test_causality_test_case_13() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
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
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }


    // RESULT: Never
    // intended allowed per http://www.cs.umd.edu/~pugh/java/memoryModel/CausalityTestCases.html
    // actually disallowed per Aspinall and Sevcik "Formalising Java's Data Race Free Guarantee" Section 4
    @Test
    fun test_causality_test_case_17() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(42, 42, 42))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t0_r3 = 0
            var t1_r2 = 0
            val t0 = thread {
                t0_r3 = x.getPlain()
                if (t0_r3 != 42) {
                    x.setPlain(42)
                }
                t0_r1 = x.getPlain()
                y.setPlain(t0_r1)
            }
            val t1 = thread {
                t1_r2 = y.getPlain()
                x.setPlain(t1_r2)
            }
            t0.join()
            t1.join()
            Triple(t0_r1, t1_r2, t0_r3)
        }
    }

    // RESULT: Never
    // intended allowed per http://www.cs.umd.edu/~pugh/java/memoryModel/CausalityTestCases.html
    // actually disallowed per Aspinall and Sevcik "Formalising Java's Data Race Free Guarantee" Section 4
    @Test
    fun test_causality_test_case_18() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(42, 42, 42))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t0_r3 = 0
            var t1_r2 = 0
            val t0 = thread {
                t0_r3 = x.getPlain()
                if (t0_r3 == 0) {
                    x.setPlain(42)
                }
                t0_r1 = x.getPlain()
                y.setPlain(t0_r1)
            }
            val t1 = thread {
                t1_r2 = y.getPlain()
                x.setPlain(t1_r2)
            }
            t0.join()
            t1.join()
            Triple(t0_r1, t1_r2, t0_r3)
        }
    }

    // ==================== jmanson-thesis ====================

    // RESULT: Sometimes
    @Test
    fun test_jmanson_thesis_fig_2_2() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(2 to 1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jmanson_thesis_fig_2_5() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 0, 1))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jmanson_thesis_fig_3_1() {
        // exists (0:r1!=0 \/ 1:r2!=0) -- disjunction with !=
        // Since x,y start at 0 and writes are conditional on non-zero reads,
        // the only possible outcome is (0,0). We forbid any (nonzero /\ nonzero).
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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
    fun test_jmanson_thesis_fig_3_3b() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(2, 2, 2))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jmanson_thesis_fig_3_9() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 2, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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
    @Test
    fun test_jmanson_thesis_fig_4_11() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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


    // RESULT: Never
    // repeat of CausalityTestCases-Test05 modulo renaming and different constant.
    @Test
    fun test_jmanson_thesis_fig_4_5() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0, 42, 42))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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
    fun test_jmanson_thesis_fig_4_6() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0, 42, 42))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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
    fun test_jmanson_thesis_fig_4_8() {
        // exists (0:aoobe!=1 /\ 0:r1=1 /\ 0:r2=1 /\ 1:r3=1)
        // aoobe is only set to 1 when r1 >= 2, so aoobe!=1 is implied by r1==1.
        // We approximate the forbidden outcome as (1, 1, 1) for (r1, r2, r3).
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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
    fun test_jmanson_thesis_fig_4_9() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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

    // ==================== jcstress-java5 ====================

    // RESULT: Sometimes
    @Test
    fun test_jcstress_advanced_15_volatiles_are_not_fences() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 0, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_basics_05_coherence_same_read_plain() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_basics_05_coherence_same_read_volatile() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_basics_06_causality_message_passing_plain() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_basics_06_causality_message_passing_volatile() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_basics_07_consensus_dekker_plain() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_basics_07_consensus_dekker_volatile() {
        val outcomes: Set<Pair<Int, Int>> = setOf(1 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(outcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_rmw_09_gas_effects_1_cts_cts() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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

    // ==================== jcstress-varhandle ====================

    // RESULT: Sometimes
    @Test
    fun test_jcstress_advanced_02_multi_copy_atomic_opaque() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_advanced_03_non_mca_coherence() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 2, 2, 1), listOf(2, 1, 1, 2))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_basics_05_coherence_same_read_opaque() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_basics_06_causality_message_passing_acqrel() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_basics_06_causality_message_passing_opaque() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_basics_07_consensus_dekker_acqrel() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)
        litmusTest(assertSame(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_rmw_09_gas_effects_2_cas_cas() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_rmw_09_gas_effects_3_gts_cas() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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
    fun test_jcstress_rmw_09_gas_effects_4_gas_cas() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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

    // ==================== standard-variations ====================

    // RESULT: Sometimes
    @Test
    fun test_2plus2W_opq() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setOpaque(2)
                y.setOpaque(1)
                t0_r1 = x.getOpaque()
            }
            val t1 = thread {
                y.setOpaque(2)
                x.setOpaque(1)
                t1_r2 = y.getOpaque()
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Never
    @Test
    fun test_lb_if() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                t0_r1 = x.getPlain()
                if (t0_r1 != 0) {
                    y.setPlain(1)
                }
            }
            val t1 = thread {
                t1_r2 = y.getPlain()
                if (t1_r2 != 0) {
                    x.setPlain(1)
                }
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // You should not see LB for opaque? TODO: Investigate the origins of this test case


    // RESULT: Never
    @Test
    fun test_lb_ra() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                t0_r1 = y.getAcquire()
                x.setRelease(1)
            }
            val t1 = thread {
                t1_r2 = x.getAcquire()
                y.setRelease(1)
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun test_mp_2_access() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 1, 1, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var t1_a = 0
            var t1_b = 0
            var t2_c = 0
            var t2_d = 0
            val t0 = thread {
                x.setPlain(1)
                y.setRelease(1)
                z.setPlain(1)
            }
            val t1 = thread {
                t1_a = y.getAcquire()
                t1_b = x.getPlain()
            }
            val t2 = thread {
                t2_c = z.getAcquire()
                t2_d = x.getPlain()
            }
            t0.join()
            t1.join()
            t2.join()
            listOf(t1_a, t1_b, t2_c, t2_d)
        }
    }
    // RESULT: Never
    @Test
    fun test_mp_fadd_vol() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 2, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t2_r2 = 0
            var t2_r3 = 0
            val t0 = thread {
                x.setPlain(42)
                y.setRelease(1)
            }
            val t1 = thread {
                t1_r1 = y.getAndAdd(1)
            }
            val t2 = thread {
                t2_r2 = y.getAcquire()
                t2_r3 = x.getPlain()
            }
            t0.join()
            t1.join()
            t2.join()
            listOf(t1_r1, t2_r2, t2_r3)
        }
    }

    // RESULT: Sometimes
    @Test
    fun test_mp_fake_fence() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(1 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val v = AtomicInteger(0)
            var t1_r2 = 0
            var t1_r4 = 0
            val t0 = thread {
                x.setPlain(1)
                var r1 = v.get()
                v.set(r1)
                y.setPlain(1)
            }
            val t1 = thread {
                t1_r2 = y.getPlain()
                var r3 = v.get()
                v.set(r3)
                t1_r4 = x.getPlain()
            }
            t0.join()
            t1.join()
            t1_r2 to t1_r4
        }
    }

    @Test
    fun test_mp_fence() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 0, 0))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val f = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            var t1_r3 = 0
            val t0 = thread {
                x.setPlain(1)
                y.setPlain(1)
                VarHandle.releaseFence()
                f.setPlain(1)
            }
            val t1 = thread {
                t1_r1 = f.getPlain()
                t1_r2 = x.getPlain()
                VarHandle.acquireFence()
                t1_r3 = y.getPlain()
            }
            t0.join()
            t1.join()
            Triple(t1_r1, t1_r2, t1_r3)
        }
    }

    // RESULT: Never
    @Test
    fun test_mp_large_transfer() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val f = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            var t1_r3 = 0
            val t0 = thread {
                x.setPlain(1)
                y.setPlain(1)
                f.setRelease(1)
            }
            val t1 = thread {
                t1_r1 = f.getAcquire()
                t1_r2 = y.getPlain()
                t1_r3 = x.getPlain()
            }
            t0.join()
            t1.join()
            Triple(t1_r1, t1_r2, t1_r3)
        }
    }

    // RESULT: Sometimes
    @Test
    fun test_mp_once_two_unsync_writers() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0, 1, 2))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t2_r3 = 0
            var t2_r4 = 0
            val t0 = thread {
                t0_r1 = y.get()
                x.setPlain(1)
                y.set(1)
            }
            val t1 = thread {
                var r2 = y.get()
                x.setPlain(2)
                y.set(1)
            }
            val t2 = thread {
                t2_r3 = y.get()
                t2_r4 = x.getPlain()
            }
            t0.join()
            t1.join()
            t2.join()
            Triple(t0_r1, t2_r3, t2_r4)
        }
    }

    // RESULT: Never
    @Test
    fun test_mp_overwrite() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setPlain(1)
                x.setPlain(2)
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

    // RESULT: Never
    @Test
    fun test_mp_quasi_fence() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 0)
        // NOTE: see test sb quasi fence as this test is similar to it
        litmusTest(assertSometimes(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val v = AtomicInteger(0)
            var t1_r2 = 0
            var t1_r4 = 0
            val t0 = thread {
                x.setPlain(1)
                v.set(0)
                var r1 = v.get()
                y.setPlain(1)
            }
            val t1 = thread {
                t1_r2 = y.getPlain()
                v.set(0)
                var r3 = v.get()
                t1_r4 = x.getPlain()
            }
            t0.join()
            t1.join()
            t1_r2 to t1_r4
        }
    }

    // RESULT: Never
    @Test
    fun test_mp_trans() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(2, 2, 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t2_r2 = 0
            var t2_r3 = 0
            val t0 = thread {
                x.setRelease(1)
                y.setRelease(2)
            }
            val t1 = thread {
                t1_r1 = y.getAcquire()
                x.setRelease(2)
            }
            val t2 = thread {
                t2_r2 = x.getAcquire()
                t2_r3 = x.getAcquire()
            }
            t0.join()
            t1.join()
            t2.join()
            Triple(t1_r1, t2_r2, t2_r3)
        }
    }

    // RESULT: Never
    @Test
    fun test_no_future_read_opq() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            var t0_r1 = 0
            val t0 = thread {
                t0_r1 = x.getOpaque()
                x.setOpaque(1)
            }
            t0.join()
            t0_r1
        }
    }

    // RESULT: Never
    @Test
    fun test_no_future_read_pln() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            var t0_r1 = 0
            val t0 = thread {
                t0_r1 = x.getPlain()
                x.setPlain(1)
            }
            t0.join()
            t0_r1
        }
    }

    // RESULT: Never
    @Test
    fun test_no_future_read_ra() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            var t0_r1 = 0
            val t0 = thread {
                t0_r1 = x.getAcquire()
                x.setRelease(1)
            }
            t0.join()
            t0_r1
        }
    }

    // RESULT: Never
    @Test
    fun test_no_future_read_vol() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            var t0_r1 = 0
            val t0 = thread {
                t0_r1 = x.get()
                x.set(1)
            }
            t0.join()
            t0_r1
        }
    }

    // RESULT: Sometimes
    @Test
    fun test_release_sequence_opq() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(2 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setOpaque(1)
                y.setRelease(1)
                y.setOpaque(2)
            }
            val t1 = thread {
                t1_r1 = y.getAcquire()
                t1_r2 = x.getOpaque()
            }
            t0.join()
            t1.join()
            t1_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun test_release_sequence_pln() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(2 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setPlain(1)
                y.setRelease(1)
                y.setPlain(2)
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
    fun test_release_sequence_rmw() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 2, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            var t1_r3 = 0
            val t0 = thread {
                x.setPlain(1)
                y.setRelease(1)
                t0_r1 = y.compareAndExchangeRelease(1, 2)
            }
            val t1 = thread {
                t1_r2 = y.getAcquire()
                t1_r3 = x.getPlain()
            }
            t0.join()
            t1.join()
            Triple(t0_r1, t1_r2, t1_r3)
        }
    }

    // RESULT: Sometimes
    @Test
    fun test_sb_fadd_volatile_acquire() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                var x0 = x.getAndAdd(1)
                t0_r1 = y.getAcquire()
            }
            val t1 = thread {
                var y0 = y.getAndAdd(1)
                t1_r2 = x.getAcquire()
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    // RESULT: Sometimes
    @Test
    fun test_sb_fake_fence() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val v = AtomicInteger(0)
            var t0_r2 = 0
            var t1_r4 = 0
            val t0 = thread {
                x.setPlain(1)
                var r1 = v.get()
                v.set(r1)
                t0_r2 = y.getPlain()
            }
            val t1 = thread {
                y.setPlain(1)
                var r3 = v.get()
                v.set(r3)
                t1_r4 = x.getPlain()
            }
            t0.join()
            t1.join()
            t0_r2 to t1_r4
        }
    }

    // RESULT: Sometimes
    @Test
    fun test_SB_opq_vol() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r2 = 0
            val t0 = thread {
                x.setOpaque(1)
                t0_r1 = y.get()
            }
            val t1 = thread {
                y.setOpaque(1)
                t1_r2 = x.get()
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r2
        }
    }

    @Test
    fun test_sb_quasi_fence() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        //NOTE: JCstress observes this outcome on arm, even though it is banned by JMM
        litmusTest(assertSometimes(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val v = AtomicInteger(0)
            var t0_r2 = 0
            var t1_r4 = 0
            val t0 = thread {
                x.setPlain(1)
                v.set(0)
                var r1 = v.get()
                t0_r2 = y.getPlain()
            }
            val t1 = thread {
                y.setPlain(1)
                v.set(0)
                var r3 = v.get()
                t1_r4 = x.getPlain()
            }
            t0.join()
            t1.join()
            t0_r2 to t1_r4
        }
    }

    // ===================== IGNORED TESTS =====================

    @Ignore
    @Test
    fun testPPOCA() {
        class TestPPOCA {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
                VarHandle.fullFence()
                y.setOpaque(1)
            }
            fun thread1(): Triple<Int, Int, Int> {
                val x0 = y.getOpaque()
                var x4 = 0
                var x6 = 0
                if (x0 == 0) {
                    z.setOpaque(1)
                    x4 = z.getOpaque()
                    x6 = x.getOpaque()
                }
                return Triple(x0, x4, x6)
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestPPOCA::thread0) }
                thread { actor(TestPPOCA::thread1) }
            }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 0))
        litmusTest(TestPPOCA::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.JAM21) { results ->
            getValue<Triple<Int, Int, Int>>(results.parallelResults[1][0]!!)
        }
    }

    @Ignore
    @Test
    fun testSBMfence() {
        class TestSBMfence {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0(): Int {
                x.setOpaque(1)
                VarHandle.fullFence()
                return y.getOpaque()
            }
            fun thread1(): Int {
                y.setOpaque(1)
                VarHandle.fullFence()
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestSBMfence::thread0) }
                thread { actor(TestSBMfence::thread1) }
            }
        }
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0))
        litmusTest(TestSBMfence::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            val eax0 = getValue<Int>(results.parallelResults[0][0]!!)
            val eax1 = getValue<Int>(results.parallelResults[1][0]!!)
            eax0 to eax1
        }
    }

    @Ignore
    @Test
    fun testWRWC() {
        class TestWRWC {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
                z.setRelease(1)
            }
            fun thread1(): Pair<Int, Int> {
                val r1 = z.getAcquire()
                VarHandle.fullFence()
                val r2 = y.getOpaque()
                return r1 to r2
            }
            fun thread2(): Int {
                y.setOpaque(1)
                VarHandle.fullFence()
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestWRWC::thread0) }
                thread { actor(TestWRWC::thread1) }
                thread { actor(TestWRWC::thread2) }
            }
        }
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 0, 0))
        litmusTest(TestWRWC::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.JAM21) { results ->
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val t2 = getValue<Int>(results.parallelResults[2][0]!!)
            Triple(t1.first, t1.second, t2)
        }
    }

    @Ignore
    @Test
    fun testRWCSyncs() {
        class TestRWCSyncs {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            fun thread0() {
                x.setOpaque(1)
            }
            fun thread1(): Pair<Int, Int> {
                val r1 = x.getOpaque()
                VarHandle.fullFence()
                val r2 = y.getOpaque()
                return r1 to r2
            }
            fun thread2(): Int {
                y.setOpaque(1)
                VarHandle.fullFence()
                return x.getOpaque()
            }
        }
        val testScenario = scenario {
            parallel {
                thread { actor(TestRWCSyncs::thread0) }
                thread { actor(TestRWCSyncs::thread1) }
                thread { actor(TestRWCSyncs::thread2) }
            }
        }
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1,0,0))
        litmusTest(TestRWCSyncs::class.java, testScenario, assertNever(forbiddenOutcomes), MemoryModel.JAM21) { results ->
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val r1 = getValue<Int>(results.parallelResults[2][0]!!)
            Triple(t1.first, t1.second, r1)
        }
    }

    // RESULT: Sometimes
    // TODO: fences are not supported by the event structure strategy
    @Ignore
    @Test
    fun test_jcstress_advanced_02_multi_copy_atomic_fenced() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
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
    // TODO: fences are not supported by the event structure strategy
    @Ignore
    @Test
    fun test_jcstress_advanced_02_multi_copy_atomic_fully_fenced() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
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

    // IGNORED_RESULT: Never
    // TODO: fences are not supported by the event structure strategy
    @Ignore
    @Test
    fun test_iriw_sensibly_fenced() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t2_r1 = 0
            var t2_r2 = 0
            var t3_r3 = 0
            var t3_r4 = 0
            val t0 = thread {
                x.setPlain(1)
            }
            val t1 = thread {
                y.setPlain(1)
            }
            val t2 = thread {
                t2_r1 = x.getPlain()
                VarHandle.fullFence()
                t2_r2 = y.getPlain()
            }
            val t3 = thread {
                t3_r3 = y.getPlain()
                VarHandle.fullFence()
                t3_r4 = x.getPlain()
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(t2_r1, t2_r2, t3_r3, t3_r4)
        }
    }

    // RESULT: Sometimes
    @Ignore // LOAD BUFFERING
    @Test
    fun test_lb_fake_fence() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val v = AtomicInteger(0)
            var t0_r1 = 0
            var t1_r3 = 0
            val t0 = thread {
                t0_r1 = x.getPlain()
                var r2 = v.get()
                v.set(t0_r1)
                y.setPlain(1)
            }
            val t1 = thread {
                t1_r3 = y.getPlain()
                var r4 = v.get()
                v.set(r4)
                x.setPlain(1)
            }
            t0.join()
            t1.join()
            t0_r1 to t1_r3
        }
    }

    // RESULT: Never
    // TODO: fences are not supported by the event structure strategy
    @Ignore
    @Test
    fun test_mp_2_fence() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 1, 1, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var t1_a = 0
            var t1_b = 0
            var t2_c = 0
            var t2_d = 0
            val t0 = thread {
                x.setPlain(1)
                VarHandle.releaseFence()
                y.setPlain(1)
                z.setPlain(1)
            }
            val t1 = thread {
                t1_a = y.getAcquire()
                t1_b = x.getPlain()
            }
            val t2 = thread {
                t2_c = z.getAcquire()
                t2_d = x.getPlain()
            }
            t0.join()
            t1.join()
            t2.join()
            listOf(t1_a, t1_b, t2_c, t2_d)
        }
    }

    // IGNORED_RESULT: Sometimes
    @Ignore
    @Test
    fun test_mp_fadd_acq() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 2, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t2_r2 = 0
            var t2_r3 = 0
            val t0 = thread {
                x.setPlain(42)
                y.setRelease(1)
            }
            val t1 = thread {
                t1_r1 = y.getAndAdd(1)
            }
            val t2 = thread {
                t2_r2 = y.getAcquire()
                t2_r3 = x.getPlain()
            }
            t0.join()
            t1.join()
            t2.join()
            listOf(t1_r1, t2_r2, t2_r3)
        }
    }

    // IGNORED_RESULT: Sometimes
    @Ignore
    @Test
    fun test_mp_fadd_rel() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 2, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.JAM21) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t1_r1 = 0
            var t2_r2 = 0
            var t2_r3 = 0
            val t0 = thread {
                x.setPlain(42)
                y.setRelease(1)
            }
            val t1 = thread {
                t1_r1 = y.getAndAdd(1)
            }
            val t2 = thread {
                t2_r2 = y.getAcquire()
                t2_r3 = x.getPlain()
            }
            t0.join()
            t1.join()
            t2.join()
            listOf(t1_r1, t2_r2, t2_r3)
        }
    }
    // IGNORED: fun testIriwScFences -> No results in the paper
    // IGNORED: fun testPushSimple -> No results in the paper
    // IGNORED: fun testRmwChains -> No results in the paper

    // IGNORED: fun testCppMemScAtomics -> Causality
    // IGNORED: fun test_SA08_redundant_read_after_read_elimination_target() -> Causality
    // IGNORED: fun test_LBP21_vread_vread_merging_source() -> Causality
    // IGNORED: fun test_LBP21_vread_vread_merging_target() -> Causality
    // IGNORED: fun test_LBP21_vwrite_vwrite_merging_source() -> Causality
    // IGNORED: fun test_LBP21_vwrite_vwrite_merging_target() -> Causality
    // IGNORED: fun test_LBP21_write_aread_merging_source() -> Causality
    // IGNORED: fun test_LBP21_write_aread_merging_target() -> Causality
    // IGNORED: fun test_causality_test_case_01() -> Causality
    // IGNORED: fun test_causality_test_case_02() -> Causality
    // IGNORED: fun test_causality_test_case_03() -> Causality
    // IGNORED: fun test_causality_test_case_06() -> Causality
    // IGNORED: fun test_causality_test_case_07() -> Causality
    // IGNORED: fun test_causality_test_case_08() -> Causality
    // IGNORED: fun test_causality_test_case_09() -> Causality
    // IGNORED: fun test_causality_test_case_11() -> Causality
    // IGNORED: fun test_causality_test_case_16() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_1_3() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_2_1() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_3_3a() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_3_5() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_3_6() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_4_10() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_4_12() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_4_7() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_5_1() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_8_1() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_8_2() -> Causality
    // IGNORED: fun test_jmanson_thesis_fig_8_3() -> Causality
    // IGNORED: fun test_lb_opq() -> Causality
    // IGNORED: fun test_lb_plain_constant_variable() -> Causality
    // IGNORED: fun test_out_of_thin_air_constant_conditional() -> Causality
    // IGNORED: fun test_out_of_thin_air_constant_conditional_variable_unconditional() -> Causality
    // IGNORED: fun test_out_of_thin_air_constant() -> Causality
    // IGNORED: fun test_out_of_thin_air_variable() -> Causality
    // IGNORED: fun test_causality_test_case_04 -> causality test
    // IGNORED: fun test_jmanson_thesis_fig_1_2 -> causality test
    // IGNORED: fun test_jmanson_thesis_fig_3_10 -> causality test
    // IGNORED: fun test_lb_quasi_fence -> causality test

    // IGNORED: fun testWRR -> Same as test_jcstress_basics_05_coherence_same_read_opaque
    // IGNORED: fun test_rseq_weak -> same as testRseqWeak
    // IGNORED: fun test_iriw_acq_vol -> same as testRseqWeak, aslo has an unknown result?
    // IGNORED: fun test_sb_rfis -> same as testSbPlusRfis
    // IGNORED: fun test_iriw_vol -> same as testIriwSc
    // IGNORED: fun test_iriw_acq_acq -> same as testCppMemIriwRelacq
    // IGNORED: fun test_jcstress_UnobservedVolatileBarrierTest -> same as test_jcstress_advanced_15_volatiles_are_not_fences
    // IGNORED: fun test_jcstress_basics_05_coherence_same_read_opaque_0_1 -> merged to test_jcstress_basics_05_coherence_same_read_opaque
    // IGNORED: fun test_jcstress_basics_05_coherence_same_read_opaque_1_0 -> merged to test_jcstress_basics_05_coherence_same_read_opaque
    // IGNORED: fun test_jcstress_basics_05_coherence_same_read_opaque_1_1 -> merged to test_jcstress_basics_05_coherence_same_read_opaque
    // IGNORED: fun test_jcstress_basics_05_coherence_same_read_plain_0_1 -> merged into test_jcstress_basics_05_coherence_same_read_plain
    // IGNORED: fun test_jcstress_basics_05_coherence_same_read_plain_1_0 -> merged into test_jcstress_basics_05_coherence_same_read_plain
    // IGNORED: fun test_jcstress_basics_05_coherence_same_read_plain_1_1 -> merged into test_jcstress_basics_05_coherence_same_read_plain
    // IGNORED: fun test_jcstress_basics_05_coherence_same_read_volatile_0_1 -> merged into test_jcstress_basics_05_coherence_same_read_volatile
    // IGNORED: fun test_jcstress_basics_05_coherence_same_read_volatile_1_0 -> merged into test_jcstress_basics_05_coherence_same_read_volatile
    // IGNORED: fun test_jcstress_basics_05_coherence_same_read_volatile_1_1 -> merged into test_jcstress_basics_05_coherence_same_read_volatile
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_plain_0_1 -> merged into test_jcstress_basics_06_causality_message_passing_plain
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_plain_1_0 -> merged into test_jcstress_basics_06_causality_message_passing_plain
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_plain_1_1 -> merged into test_jcstress_basics_06_causality_message_passing_plain
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_volatile_0_1 -> merged into test_jcstress_basics_06_causality_message_passing_volatile
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_volatile_1_0 -> merged into test_jcstress_basics_06_causality_message_passing_volatile
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_volatile_1_0 -> merged into test_jcstress_basics_06_causality_message_passing_volatile
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_plain_0_1 -> merged into test_jcstress_basics_07_consensus_dekker_plain
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_plain_1_0 -> merged into test_jcstress_basics_07_consensus_dekker_plain
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_plain_1_1 -> merged into test_jcstress_basics_07_consensus_dekker_plain
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_volatile_0_1 -> merged into test_jcstress_basics_07_consensus_dekker_volatile
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_volatile_1_0 -> merged into test_jcstress_basics_07_consensus_dekker_volatile
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_volatile_1_1 -> merged into test_jcstress_basics_07_consensus_dekker_volatile
    // IGNORED: fun test_jcstress_advanced_03_non_mca_coherence_2112 -> merged into test_jcstress_advanced_03_non_mca_coherence
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_acqrel_0_1 -> merged into test_jcstress_basics_06_causality_message_passing_acqrel
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_acqrel_1_0 -> merged into test_jcstress_basics_06_causality_message_passing_acqrel
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_acqrel_1_1 -> merged into test_jcstress_basics_06_causality_message_passing_acqrel
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_opaque_0_1 -> Merged into test_jcstress_basics_06_causality_message_passing_opaque
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_opaque_1_0 -> Merged into test_jcstress_basics_06_causality_message_passing_opaque
    // IGNORED: fun test_jcstress_basics_06_causality_message_passing_opaque_1_1 -> Merged into test_jcstress_basics_06_causality_message_passing_opaque
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_acqrel_0_1 -> Merged into test_jcstress_basics_07_consensus_dekker_acqrel
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_acqrel_1_0 -> Merged into test_jcstress_basics_07_consensus_dekker_acqrel
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_acqrel_1_1 -> Merged into test_jcstress_basics_07_consensus_dekker_acqrel
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_opaque_0_1 -> Merged into testSBOpaque
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_opaque_1_0 -> Merged into testSBOpaque
    // IGNORED: fun test_jcstress_basics_07_consensus_dekker_opaque_1_1 -> Merged into testSBOpaque

    // IGNORED: test_jcstress_basics_07_consensus_dekker_opaque_0_0 -> Renamed to test_jcstress_basics_07_consensus_dekker_opaque
    // IGNORED: test_jcstress_basics_07_consensus_dekker_acqrel_0_0 -> renamed into test_jcstress_basics_07_consensus_dekker_acqrel
    // IGNORED: test_jcstress_basics_06_causality_message_passing_opaque_0_0 -> Renamed into test_jcstress_basics_06_causality_message_passing_opaque
    // IGNORED: test_jcstress_basics_06_causality_message_passing_acqrel_0_0 -> merged into test_jcstress_basics_06_causality_message_passing_acqrel
    // IGNORED: test_jcstress_advanced_03_non_mca_coherence_1221 -> renamed into test_jcstress_advanced_03_non_mca_coherence
    // IGNORED: test_jcstress_basics_07_consensus_dekker_volatile_0_0 -> renamed into test_jcstress_basics_07_consensus_dekker_volatile
    // IGNORED: test_jcstress_basics_07_consensus_dekker_plain_0_0 -> renamed into test_jcstress_basics_07_consensus_dekker_plain
    // IGNORED: test_jcstress_basics_06_causality_message_passing_volatile_0_0 -> renamed into test_jcstress_basics_06_causality_message_passing_volatile
    // IGNORED: test_jcstress_basics_06_causality_message_passing_plain_0_0 -> renamed into test_jcstress_basics_06_causality_message_passing_plain
    // IGNORED: test_jcstress_basics_05_coherence_same_read_volatile_0_0 -> renamed into test_jcstress_basics_05_coherence_same_read_volatile
    // IGNORED: test_jcstress_basics_05_coherence_same_read_plain_0_0 -> renamed into test_jcstress_basics_05_coherence_same_read_plain
    // IGNORED: test_jcstress_basics_05_coherence_same_read_opaque_0_0 -> renamed to test_jcstress_basics_05_coherence_same_read_opaque
}


