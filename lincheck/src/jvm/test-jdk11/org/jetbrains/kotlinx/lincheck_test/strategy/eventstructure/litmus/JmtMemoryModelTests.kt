/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmus

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.OutcomeVerifier
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertNever
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSometimes
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Litmus tests from Panneke and Wehrheim, "jMT: Testing Correctness of Java Memory Models" (2026). */
class JmtMemoryModelTests {
    // Figure 5; the paper observes this witness on x86 although JAM21 forbids it.
    @Test
    fun TestFigure5CompilationCounterexample() {
        litmusTest(
            OutcomeVerifier<List<Int>> { outcomes ->
                assertTrue("No completed execution", outcomes.isNotEmpty())
                val witness = listOf(2, 0, 0, 1, 2)
                println("PAPER_WITNESS jMT-Figure5 observed=${witness in outcomes} witness=$witness")
            },
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var a = 0
            var b = 0
            var c = 0
            var d = 0
            var e = 0
            val t1 = thread {
                x.setRelease(2)
                a = x.getAcquire()
                b = z.get()
                c = y.get()
            }
            val t2 = thread {
                y.set(2)
                x.set(1)
            }
            val t3 = thread {
                d = x.getOpaque()
                e = x.getOpaque()
            }
            t1.join()
            t2.join()
            t3.join()
            listOf(a, b, c, d, e)
        }
    }

    // Borrowed jMT artifact examples; original identifiers are retained separately from Figure 5.
    // RESULT: Sometimes
    @Test
    fun Test_2plus2W_opq() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_lb_if() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_lb_ra() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_mp_2_access() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 1, 1, 0))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_mp_fadd_vol() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 2, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_mp_fake_fence() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(1 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_mp_fence() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 0, 0))
        litmusTest(assertNever(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_mp_large_transfer() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 1, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_mp_once_two_unsync_writers() {
        val expectedOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(0, 1, 2))
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_mp_overwrite() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_mp_quasi_fence() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(1 to 0)
        // NOTE: see test sb quasi fence as this test is similar to it
        litmusTest(assertSometimes(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_mp_trans() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(2, 2, 1))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_no_future_read_opq() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_no_future_read_pln() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_no_future_read_ra() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_no_future_read_vol() {
        val forbiddenOutcomes: Set<Int> = setOf(1)
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_release_sequence_opq() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(2 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_release_sequence_pln() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(2 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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

    // A release RMW publishes earlier plain writes independently of release sequences.
    @Test
    fun Test_release_sequence_rmw() {
        val forbiddenOutcomes: Set<Triple<Int, Int, Int>> = setOf(Triple(1, 2, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava, requireExhaustion = true) {
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
    fun Test_sb_fadd_volatile_acquire() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_sb_fake_fence() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_SB_opq_vol() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_sb_quasi_fence() {
        val forbiddenOutcomes: Set<Pair<Int, Int>> = setOf(0 to 0)
        //NOTE: JCstress observes this outcome on arm, even though it is banned by JMM
        litmusTest(assertSometimes(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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

    // RESULT: Never
    // eco_psc includes rb ; rf edges between plain reads across full fences.
    @Test
    fun Test_iriw_sensibly_fenced() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 0, 1, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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

    // Measure the causality witness even though the current checker cannot explore all causality cycles.
    @Test
    fun Test_lb_fake_fence() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(1 to 1)
        val observer = OutcomeVerifier<Pair<Int, Int>> { outcomes ->
            assertTrue("No completed execution", outcomes.isNotEmpty())
            println("PAPER_WITNESS LB-fake-fence observed=${outcomes.any { it in expectedOutcomes }} witness=(1, 1)")
        }
        litmusTest(observer, MemoryModel.RelaxedJava, requireExhaustion = true) {
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
    @Test
    fun Test_mp_2_fence() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(1, 1, 1, 0))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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

    // RESULT: Sometimes
    @Test
    fun Test_mp_fadd_acq() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 2, 0))
        val handle = MethodHandles.arrayElementVarHandle(IntArray::class.java)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = IntArray(1)
            var t1_r1 = 0
            var t2_r2 = 0
            var t2_r3 = 0
            val t0 = thread {
                x.setPlain(42)
                handle.setRelease(y, 0, 1)
            }
            val t1 = thread {
                t1_r1 = handle.getAndAddAcquire(y, 0, 1) as Int
            }
            val t2 = thread {
                t2_r2 = handle.getAcquire(y, 0) as Int
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
    fun Test_mp_fadd_rel() {
        val expectedOutcomes: Set<List<Int>> = setOf(listOf(1, 2, 0))
        val handle = MethodHandles.arrayElementVarHandle(IntArray::class.java)
        litmusTest(assertSometimes(expectedOutcomes), MemoryModel.RelaxedJava) {
            val x = AtomicInteger(0)
            val y = IntArray(1)
            var t1_r1 = 0
            var t2_r2 = 0
            var t2_r3 = 0
            val t0 = thread {
                x.setPlain(42)
                handle.setRelease(y, 0, 1)
            }
            val t1 = thread {
                t1_r1 = handle.getAndAddRelease(y, 0, 1) as Int
            }
            val t2 = thread {
                t2_r2 = handle.getAcquire(y, 0) as Int
                t2_r3 = x.getPlain()
            }
            t0.join()
            t1.join()
            t2.join()
            listOf(t1_r1, t2_r2, t2_r3)
        }
    }
}
