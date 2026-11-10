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

import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.UNKNOWN
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertNever
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSame
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSometimes
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.getValue
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.Test
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Litmus tests from Bender and Palsberg, "A Formalization of Java’s Concurrent Access Modes" (2019). */
class JamMemoryModelTests {

    @Test
    fun TestSBReleaseOpaque() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0), (0 to 1), (1 to 1), (1 to 0))
        litmusTest(assertSame(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestSBOpaque() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0), (0 to 1), (1 to 1), (1 to 0))
        litmusTest(assertSame(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                x.setOpaque(1)
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
    fun Test4SB() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(0,0,0,0))
        litmusTest(assertSometimes(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test6SB() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(0,0,0,0,0,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestArfna() {
        // x=1 /\ y=1, should never happen.
        val forbiddentOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddentOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestArfnaTransformed() {
        // x=1 /\ y=1, should never happen.
        val forbiddentOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddentOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestB() {
        //NOTE: This is just load buffering, I am not sure why the name is like that.
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestBReorder() {
        val allowedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertSometimes(allowedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestC() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestCReorder() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestCoRWR() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val eax = x.getOpaque()
            x.setOpaque(1)
            val ebx = x.getOpaque()
            eax to ebx
        }
    }

    @Test
    fun TestCyc() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestFig1() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1,1,1))
        litmusTest(assertSame(expectedOutcomes, UNKNOWN), MemoryModel.RelaxedJava) {
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
    fun TestIriwInternal() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestMpRelaxed() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestPodrw001() {
        // NOTE: this is just Store Buffering with 3 reads
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0,0,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestX001() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0,1,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestX003() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(2,2,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestX006() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((2 to 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestX86_2plus2W() {
        val expectedOutcomes: Set<Pair<Int,Int>> = setOf((2 to 2))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestA1() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestA1Reorder() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestA3() {
        val expectedOutcomes: Set<Int> = setOf(1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestA3Reorder() {
        val expectedOutcomes: Set<Int> = setOf(1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestIRIWPoaasLL() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestIRIWPoapsLL() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestLinearisation() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,1,1,1,1))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestLinearisation2() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,1,1,1,1))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestMpRelacq() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.RelaxedJava) {
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

    // NOTE: this test is interesting because C11 forbids this behavior but JMM allows it
    @Test
    fun TestMpRelacqRs() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((2 to 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestRoachmotel() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,1,1,1))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestRoachmotel2() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,1,1,1))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestRseqWeak() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((3 to 1))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestRseqWeak2() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((3 to 1))
        litmusTest(assertSame(expectedOutcomes, UNKNOWN), MemoryModel.RelaxedJava) {
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
    fun TestTotalco() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1,1,1))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestWWRRWWRRWsilpPoaaWsilpPoaa() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,2,2,0,2,0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestMpFences() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun TestFig6() {
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
        litmusTest(TestFig6::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava, 10_000) { results ->
            val r = getValue<Int>(results.parallelResults[2][0]!!)
            val t3 = getValue<List<Int>>(results.parallelResults[3][0]!!)
            listOf(r, t3[0], t3[1], t3[2], t3[3], t3[4], t3[5])
        }
    }

    @Test
    fun TestFig6Translated() {
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
        litmusTest(TestFig6Translated::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava, 10_000) { results ->
            val r = getValue<Int>(results.parallelResults[2][0]!!)
            val t3 = getValue<List<Int>>(results.parallelResults[3][0]!!)
            listOf(r, t3[0], t3[1], t3[2], t3[3], t3[4], t3[5])
        }
    }

    @Test
    fun TestIriwAcqSc() {
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
        litmusTest(TestIriwAcqSc::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun TestIriwScRlxAcq() {
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
        litmusTest(TestIriwScRlxAcq::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun TestIriwSc() {
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
        litmusTest(TestIriwSc::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun TestMpSc() {
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
        litmusTest(TestMpSc::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun TestZ6U() {
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
        litmusTest(TestZ6U::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val t2 = getValue<Int>(results.parallelResults[2][0]!!)
            listOf(t1.first, t1.second, t2)
        }
    }

    @Test
    fun TestA3v2() {
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
        litmusTest(TestA3v2::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Int>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun TestCp() {
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
        litmusTest(TestCp::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun TestCpReorder() {
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
        litmusTest(TestCpReorder::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun TestCpq() {
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
        litmusTest(TestCpq::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun TestCpqReorder() {
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
        litmusTest(TestCpqReorder::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun TestCq() {
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
        litmusTest(TestCq::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun TestCqReorder() {
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
        litmusTest(TestCqReorder::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Pair<Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun TestX002() {
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
        litmusTest(TestX002::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val eax0 = getValue<Int>(results.parallelResults[0][0]!!)
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            listOf(eax0, t1.first, t1.second)
        }
    }

    @Test
    fun TestX005() {
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
        litmusTest(TestX005::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val eax0 = getValue<Int>(results.parallelResults[0][0]!!)
            val eax1 = getValue<Int>(results.parallelResults[1][0]!!)
            eax0 to eax1
        }
    }

    @Test
    fun TestSbPlusRfis() {
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
        //NOTE: this behaviour is forbidden by JMM, but actually happens in practice as mentioned in the JMT paper
        // https://arxiv.org/pdf/2604.15978, section 7, scenario 3
        litmusTest(TestClass::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val t0 = getValue<Pair<Int,Int>>(results.parallelResults[0][0]!!)
            val t1 = getValue<Pair<Int,Int>>(results.parallelResults[1][0]!!)
            listOf(t0.first, t0.second, t1.first, t1.second)
        }
    }

    @Test
    fun TestSeq() {
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
        litmusTest(TestSeq::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Triple<Int, Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun TestSeq2() {
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
        litmusTest(TestSeq2::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Triple<Int, Int, Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun TestStrengthen() {
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
        litmusTest(TestStrengthen::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<List<Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun TestStrengthen2() {
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
        litmusTest(TestStrengthen2::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<List<Int>>(results.postResults[0]!!)
        }
    }

    @Test
    fun TestCppMemIriwRelacq() {
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
        litmusTest(TestCppMemIriwRelacq::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val t2 = getValue<Pair<Int, Int>>(results.parallelResults[2][0]!!)
            val t3 = getValue<Pair<Int, Int>>(results.parallelResults[3][0]!!)
            listOf(t2.first, t2.second, t3.first, t3.second)
        }
    }

    @Test
    fun Test2Plus2W() {
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
        litmusTest(Test2Plus2WVolatile::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val r1_0 = getValue<Int>(results.parallelResults[0][0]!!)
            val r1_1 = getValue<Int>(results.parallelResults[1][0]!!)
            r1_0 to r1_1
        }
    }

    @Test
    fun TestPPOCA() {
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
                if (x0 == 1) {
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
        litmusTest(TestPPOCA::class.java, testScenario, assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            getValue<Triple<Int, Int, Int>>(results.parallelResults[1][0]!!)
        }
    }

    @Test
    fun TestSBMfence() {
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
        litmusTest(TestSBMfence::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val eax0 = getValue<Int>(results.parallelResults[0][0]!!)
            val eax1 = getValue<Int>(results.parallelResults[1][0]!!)
            eax0 to eax1
        }
    }

    @Test
    fun TestWRWC() {
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
        litmusTest(TestWRWC::class.java, testScenario, assertNever(expectedOutcomes), MemoryModel.RelaxedJava) { results ->
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val t2 = getValue<Int>(results.parallelResults[2][0]!!)
            Triple(t1.first, t1.second, t2)
        }
    }

    @Test
    fun TestRWCSyncs() {
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
        litmusTest(TestRWCSyncs::class.java, testScenario, assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) { results ->
            val t1 = getValue<Pair<Int, Int>>(results.parallelResults[1][0]!!)
            val r1 = getValue<Int>(results.parallelResults[2][0]!!)
            Triple(t1.first, t1.second, r1)
        }
    }

}
