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
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Litmus tests from Liu, Bender and Palsberg, "Compiling Volatile Correctly in Java" (ECOOP 2022). */
class CompilingVolatileMemoryModelTests {
    // Figure 8; JAM21 forbids (1, 2, 1, 2).
    @Test
    fun TestFigure8BeforeRegisterPromotion() {
        litmusTest(
            OutcomeVerifier<List<Int>> { outcomes ->
                assertTrue("No completed execution", outcomes.isNotEmpty())
                val witness = listOf(1, 2, 1, 2)
                println("PAPER_WITNESS ECOOP22-Figure8 observed=${witness in outcomes} witness=$witness")
            },
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            var r4 = 0
            val t1 = thread {
                r1 = x.getOpaque()
                r2 = x.getOpaque()
            }
            val t2 = thread {
                r3 = y.getOpaque()
                r4 = y.getOpaque()
            }
            val t3 = thread {
                x.setOpaque(2)
                z.set(1)
                y.set(1)
            }
            val t4 = thread {
                y.set(2)
                x.set(1)
            }
            t1.join()
            t2.join()
            t3.join()
            t4.join()
            listOf(r1, r2, r3, r4)
        }
    }

    // Figure 9; JAM21 allows (1, 2, 1, 2).
    @Test
    fun TestFigure9AfterRegisterPromotion() {
        litmusTest(
            OutcomeVerifier<List<Int>> { outcomes ->
                assertTrue("No completed execution", outcomes.isNotEmpty())
                val witness = listOf(1, 2, 1, 2)
                println("PAPER_WITNESS ECOOP22-Figure9 observed=${witness in outcomes} witness=$witness")
            },
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            var r4 = 0
            val t1 = thread {
                r1 = x.getOpaque()
                r2 = x.getOpaque()
            }
            val t2 = thread {
                r3 = y.getOpaque()
                r4 = y.getOpaque()
            }
            val t3 = thread {
                x.setOpaque(2)
                y.set(1)
            }
            val t4 = thread {
                y.set(2)
                x.set(1)
            }
            t1.join()
            t2.join()
            t3.join()
            t4.join()
            listOf(r1, r2, r3, r4)
        }
    }

    // RESULT: Never
    // LBP21 Figure 1 / Listing L.1
    @Test
    fun Test_LBP21_volatile_non_sc_4() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(0, 1, 1, 2))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
    fun Test_LBP21_volatile_non_sc_5() {
        val forbiddenOutcomes: Set<List<Int>> = setOf(listOf(0, 1, 0, 1, 2))
        litmusTest(assertNever(forbiddenOutcomes), MemoryModel.RelaxedJava) {
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
}
