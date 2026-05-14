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

import java.util.concurrent.atomic.*
import org.junit.Test
import org.junit.Ignore
import java.lang.invoke.VarHandle
import kotlin.concurrent.thread

class JamMemoryModelTests {

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testSBOpaque() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((0 to 0), (0 to 1), (1 to 1), (1 to 0))
        litmusTest(assertSame(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun test4SB() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(0,0,0,0))
        litmusTest(assertSometimes(forbiddenOutcomes)) {
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


    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun test6SB() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(0,0,0,0,0,0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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

    @Ignore
    @Test
    fun testArfna() {
        // x=1 /\ y=1, should never happen. TODO: support getting final values?
        val forbiddentOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddentOutcomes)) {
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

    @Ignore
    @Test
    fun testArfnaTransformed() {
        // x=1 /\ y=1, should never happen. TODO: support getting final values?
        val forbiddentOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddentOutcomes)) {
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
        litmusTest(assertNever(forbiddenOutcomes)) {
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
        litmusTest(assertSometimes(allowedOutcomes)) {
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

    // TODO: fix model checker
    @Ignore
    @Test
    fun testC() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddenOutcomes)) {
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

    // TODO: fix model checker
    @Ignore
    @Test
    fun testCReorder() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertNever(forbiddenOutcomes)) {
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
        litmusTest(assertNever(forbiddenOutcomes)) {
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
        litmusTest(assertNever(forbiddenOutcomes)) {
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

    // TODO: accroding to the JAM19 paper, this behaviour should sometimes happen under the jvm, but it seem to be porf acyclic, so...
    @Ignore
    @Test
    fun testCycNa() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertSometimes(expectedOutcomes)) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0;
            var r1 = 0;
            val t0 = thread {
                r0 = x.getPlain()
                if (r0 != 0) y.setPlain(1)
            }
            val t1 = thread {
                r1 = y.getPlain()
                if (r1 != 0) x.setPlain(1)
            }
            t0.join()
            t1.join()
            r0 to r1
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testFig1() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1,1,1))
        litmusTest(assertSame(expectedOutcomes, UNKNOWN)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testIriwInternal() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testIRIW() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(assertSometimes(expectedOutcomes)) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var t0a = 0;
            var t0b = 0;
            var t2a = 0;
            var t2b = 0;
            val t0 = thread {
                t0a = y.getOpaque()
                t0b = x.getOpaque()
            }
            val t1 = thread {
                x.setOpaque(1)
            }
            val t2 = thread {
                t2a = x.getOpaque()
                t2b = y.getOpaque()
            }
            val t3 = thread {
                y.setOpaque(1)
            }
            t0.join()
            t1.join()
            t2.join()
            t3.join()
            listOf(t0a, t0b, t2a, t2b)
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testMpRelaxed() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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


    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testPodrw001() {
        // NOTE: this is just Store Buffering with 3 reads
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0,0,0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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

    // TODO: one day we will handle fences
    @Ignore
    @Test
    fun testRWCSyncs() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1,0,0))
        litmusTest(assertNever(forbiddenOutcomes)) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1a = 0;
            var r1b = 0;
            var r2 = 0;
            val t0 = thread {
                x.setOpaque(1)
            }
            val t1 = thread {
                r1a = x.getOpaque()
                VarHandle.fullFence()
                r1b = y.getOpaque()
            }
            val t2 = thread {
                y.setOpaque(1)
                VarHandle.fullFence()
                r2 = x.getOpaque()
            }
            t0.join()
            t1.join()
            t2.join()
            Triple(r1a, r1b, r2)
        }
    }

    @Test
    fun testWRR() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf((1 to 0))
        litmusTest(assertNever(forbiddenOutcomes)) {
            val x = AtomicInteger(0)
            var x2 = 0;
            var x3 = 0;
            val t0 = thread {
                x.setOpaque(1)
            }
            val t1 = thread {
                x2 = x.getOpaque()
                x3 = x.getOpaque()
            }
            t0.join()
            t1.join()
            x2 to x3
        }
    }

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testX001() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0,1,0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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


    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testX003() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(2,2,0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testX006() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((2 to 0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testX86_2plus2W() {
        val expectedOutcomes: Set<Pair<Int,Int>> = setOf((2 to 2))
        litmusTest(assertSometimes(expectedOutcomes)) {
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


    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testA1() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertSometimes(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testA1Reorder() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((1 to 1))
        litmusTest(assertSometimes(expectedOutcomes)) {
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
        litmusTest(assertSometimes(expectedOutcomes)) {
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
        litmusTest(assertSometimes(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testIRIWPoaasLL() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testIRIWPoapsLL() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,0,1,0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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


    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testLinearisation() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,1,1,1,1))
        litmusTest(assertNever(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testLinearisation2() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,1,1,1,1))
        litmusTest(assertNever(expectedOutcomes)) {
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
        litmusTest(assertNever(expectedOutcomes)) {
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
    @Ignore
    @Test
    fun testMpRelacqRs() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((2 to 0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testRoachmotel() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,1,1,1))
        litmusTest(assertNever(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testRoachmotel2() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1,1,1,1))
        litmusTest(assertNever(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testRseqWeak() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((3 to 1))
        litmusTest(assertSometimes(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testRseqWeak2() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf((3 to 1))
        litmusTest(assertSame(expectedOutcomes, UNKNOWN)) {
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
        litmusTest(assertNever(expectedOutcomes)) {
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

    // TODO: actual failing test, that can be fixed with improvements to do the model checker
    @Ignore
    @Test
    fun testWWRRWWRRWsilpPoaaWsilpPoaa() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(2,2,2,0,2,0))
        litmusTest(assertSometimes(expectedOutcomes)) {
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

}
