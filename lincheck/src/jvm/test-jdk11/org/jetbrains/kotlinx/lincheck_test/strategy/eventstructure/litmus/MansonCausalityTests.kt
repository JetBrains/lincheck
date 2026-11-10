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

/**
 * The original twenty causality examples, with finite one-iteration variants of the two polling examples.
 * Source: https://www.cs.umd.edu/~pugh/java/memoryModel/CausalityTestCases.html
 *
 * Catalogue-allowed outcomes are measured, not required: the checker excludes some causality cycles.
 * Cases 17 and 18 also expose a disagreement between the catalogue and JLS04, documented by jMT Table 3.
 */
class MansonCausalityTests {

    // Non-negative value analysis can remove the conditional.
    @Test
    fun TestCausality01() {
        litmusTest(
            causalityWitness(1, true, listOf(1, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            val t1 = thread {
                r1 = x.getPlain()
                if (r1 >= 0) y.setPlain(1)
            }
            val t2 = thread {
                r2 = y.getPlain()
                x.setPlain(r2)
            }
            t1.join()
            t2.join()
            listOf(r1, r2)
        }
    }

    // Redundant-read elimination can make the branch unconditional.
    @Test
    fun TestCausality02() {
        litmusTest(
            causalityWitness(2, true, listOf(1, 1, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t1 = thread {
                r1 = x.getPlain()
                r2 = x.getPlain()
                if (r1 == r2) y.setPlain(1)
            }
            val t2 = thread {
                r3 = y.getPlain()
                x.setPlain(r3)
            }
            t1.join()
            t2.join()
            listOf(r1, r2, r3)
        }
    }

    // The additional writer permits SC executions with unequal reads.
    @Test
    fun TestCausality03() {
        litmusTest(
            causalityWitness(3, true, listOf(1, 1, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t1 = thread {
                r1 = x.getPlain()
                r2 = x.getPlain()
                if (r1 == r2) y.setPlain(1)
            }
            val t2 = thread {
                r3 = y.getPlain()
                x.setPlain(r3)
            }
            val t3 = thread {
                x.setPlain(2)
            }
            t1.join()
            t2.join()
            t3.join()
            listOf(r1, r2, r3)
        }
    }

    // A value cannot arise solely from a data-dependency cycle.
    @Test
    fun TestCausality04() {
        litmusTest(
            causalityWitness(4, false, listOf(1, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            val t1 = thread {
                r1 = x.getPlain()
                y.setPlain(r1)
            }
            val t2 = thread {
                r2 = y.getPlain()
                x.setPlain(r2)
            }
            t1.join()
            t2.join()
            listOf(r1, r2)
        }
    }

    // A possible independent source in another execution does not justify thin air.
    @Test
    fun TestCausality05() {
        litmusTest(
            causalityWitness(5, false, listOf(1, 1, 0)),
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
            val t1 = thread {
                r1 = x.getPlain()
                y.setPlain(r1)
            }
            val t2 = thread {
                r2 = y.getPlain()
                x.setPlain(r2)
            }
            val t3 = thread {
                z.setPlain(1)
            }
            val t4 = thread {
                r3 = z.getPlain()
                x.setPlain(r3)
            }
            t1.join()
            t2.join()
            t3.join()
            t4.join()
            listOf(r1, r2, r3)
        }
    }

    // Both branches can be replaced by an unconditional write.
    @Test
    fun TestCausality06() {
        litmusTest(
            causalityWitness(6, true, listOf(1, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            val t1 = thread {
                r1 = x.getPlain()
                if (r1 == 1) y.setPlain(1)
            }
            val t2 = thread {
                r2 = y.getPlain()
                if (r2 == 1) x.setPlain(1)
                if (r2 == 0) x.setPlain(1)
            }
            t1.join()
            t2.join()
            listOf(r1, r2)
        }
    }

    // Moving independent reads and writes can expose the target execution.
    @Test
    fun TestCausality07() {
        litmusTest(
            causalityWitness(7, true, listOf(1, 1, 1)),
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
            val t1 = thread {
                r1 = z.getPlain()
                r2 = x.getPlain()
                y.setPlain(r2)
            }
            val t2 = thread {
                r3 = y.getPlain()
                z.setPlain(r3)
                x.setPlain(1)
            }
            t1.join()
            t2.join()
            listOf(r1, r2, r3)
        }
    }

    // Global value analysis can replace the arithmetic expression by one.
    @Test
    fun TestCausality08() {
        litmusTest(
            causalityWitness(8, true, listOf(1, 1, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t1 = thread {
                r1 = x.getPlain()
                r2 = 1 + r1 * r1 - r1
                y.setPlain(r2)
            }
            val t2 = thread {
                r3 = y.getPlain()
                x.setPlain(r3)
            }
            t1.join()
            t2.join()
            listOf(r1, r2, r3)
        }
    }

    // The extra writer distinguishes this from unconditional binary value analysis.
    @Test
    fun TestCausality09() {
        litmusTest(
            causalityWitness(9, true, listOf(1, 1, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t1 = thread {
                r1 = x.getPlain()
                r2 = 1 + r1 * r1 - r1
                y.setPlain(r2)
            }
            val t2 = thread {
                r3 = y.getPlain()
                x.setPlain(r3)
            }
            val t3 = thread {
                x.setPlain(2)
            }
            t1.join()
            t2.join()
            t3.join()
            listOf(r1, r2, r3)
        }
    }

    // The control-dependency variant of case 5 also forbids thin air.
    @Test
    fun TestCausality10() {
        litmusTest(
            causalityWitness(10, false, listOf(1, 1, 0)),
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
            val t1 = thread {
                r1 = x.getPlain()
                if (r1 == 1) y.setPlain(1)
            }
            val t2 = thread {
                r2 = y.getPlain()
                if (r2 == 1) x.setPlain(1)
            }
            val t3 = thread {
                z.setPlain(1)
            }
            val t4 = thread {
                r3 = z.getPlain()
                if (r3 == 1) x.setPlain(1)
            }
            t1.join()
            t2.join()
            t3.join()
            t4.join()
            listOf(r1, r2, r3)
        }
    }

    // One additional dataflow rung extends case 7.
    @Test
    fun TestCausality11() {
        litmusTest(
            causalityWitness(11, true, listOf(1, 1, 1, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val z = AtomicInteger(0)
            val w = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            var r4 = 0
            val t1 = thread {
                r1 = z.getPlain()
                w.setPlain(r1)
                r2 = x.getPlain()
                y.setPlain(r2)
            }
            val t2 = thread {
                r4 = w.getPlain()
                r3 = y.getPlain()
                z.setPlain(r3)
                x.setPlain(1)
            }
            t1.join()
            t2.join()
            listOf(r1, r2, r3, r4)
        }
    }

    // The array-index dependency must not manufacture the value one.
    @Test
    fun TestCausality12() {
        litmusTest(
            causalityWitness(12, false, listOf(1, 1, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            val a = intArrayOf(1, 2)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t1 = thread {
                r1 = x.getPlain()
                a[r1] = 0
                r2 = a[0]
                y.setPlain(r2)
            }
            val t2 = thread {
                r3 = y.getPlain()
                x.setPlain(r3)
            }
            t1.join()
            t2.join()
            listOf(r1, r2, r3)
        }
    }

    // The only SC behavior performs no writes; the program is correctly synchronized.
    @Test
    fun TestCausality13() {
        litmusTest(
            causalityWitness(13, false, listOf(1, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            val t1 = thread {
                r1 = x.getPlain()
                if (r1 == 1) y.setPlain(1)
            }
            val t2 = thread {
                r2 = y.getPlain()
                if (r2 == 1) x.setPlain(1)
            }
            t1.join()
            t2.join()
            listOf(r1, r2)
        }
    }

    // One iteration of the polling loop; write a only when that iteration would terminate.
    @Test
    fun TestCausality14SingleIteration() {
        litmusTest(
            causalityWitness(14, false, listOf(1, 0, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t1 = thread {
                r1 = a.getPlain()
                if (r1 == 0) y.set(1) else b.setPlain(1)
            }
            val t2 = thread {
                r2 = y.get()
                r3 = b.getPlain()
                if (r2 + r3 != 0) a.setPlain(1)
            }
            t1.join()
            t2.join()
            listOf(r1, r2, r3)
        }
    }

    // The volatile-gated variant of case 14, with the same finite polling adaptation.
    @Test
    fun TestCausality15SingleIteration() {
        litmusTest(
            causalityWitness(15, false, listOf(1, 1, 0, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val a = AtomicInteger(0)
            val b = AtomicInteger(0)
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r0 = 0
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t1 = thread {
                r0 = x.get()
                r1 = if (r0 == 1) a.getPlain() else 0
                if (r1 == 0) y.set(1) else b.setPlain(1)
            }
            val t2 = thread {
                r2 = y.get()
                r3 = b.getPlain()
                if (r2 + r3 != 0) a.setPlain(1)
            }
            val t3 = thread {
                x.set(1)
            }
            t1.join()
            t2.join()
            t3.join()
            listOf(r0, r1, r2, r3)
        }
    }

    // Both reads can observe the other thread's later write to the same plain location.
    @Test
    fun TestCausality16() {
        litmusTest(
            causalityWitness(16, true, listOf(2, 1)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            val t1 = thread {
                r1 = x.getPlain()
                x.setPlain(1)
            }
            val t2 = thread {
                r2 = x.getPlain()
                x.setPlain(2)
            }
            t1.join()
            t2.join()
            listOf(r1, r2)
        }
    }

    // The catalogue permits this witness; jMT Table 3 reports it forbidden by JLS04.
    @Test
    fun TestCausality17() {
        litmusTest(
            causalityWitness(17, true, listOf(42, 42, 42), modelForbids = true),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t1 = thread {
                r3 = x.getPlain()
                if (r3 != 42) x.setPlain(42)
                r1 = x.getPlain()
                y.setPlain(r1)
            }
            val t2 = thread {
                r2 = y.getPlain()
                x.setPlain(r2)
            }
            t1.join()
            t2.join()
            listOf(r1, r2, r3)
        }
    }

    // Like case 17, the catalogue/JLS04 disagreement is distinct from the current checker regression.
    @Test
    fun TestCausality18() {
        litmusTest(
            causalityWitness(18, true, listOf(42, 42, 42), modelForbids = true),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t1 = thread {
                r3 = x.getPlain()
                if (r3 == 0) x.setPlain(42)
                r1 = x.getPlain()
                y.setPlain(r1)
            }
            val t2 = thread {
                r2 = y.getPlain()
                x.setPlain(r2)
            }
            t1.join()
            t2.join()
            listOf(r1, r2, r3)
        }
    }

    // Joining the conditional writer splits case 17 across three threads.
    @Test
    fun TestCausality19() {
        litmusTest(
            causalityWitness(19, true, listOf(42, 42, 42)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t3 = thread {
                r3 = x.getPlain()
                if (r3 != 42) x.setPlain(42)
            }
            val t1 = thread {
                t3.join()
                r1 = x.getPlain()
                y.setPlain(r1)
            }
            val t2 = thread {
                r2 = y.getPlain()
                x.setPlain(r2)
            }
            t1.join()
            t2.join()
            t3.join()
            listOf(r1, r2, r3)
        }
    }

    // Joining the conditional writer splits case 18 across three threads.
    @Test
    fun TestCausality20() {
        litmusTest(
            causalityWitness(20, true, listOf(42, 42, 42)),
            MemoryModel.RelaxedJava,
            invocations = 10_000,
            requireExhaustion = true,
        ) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0
            var r2 = 0
            var r3 = 0
            val t3 = thread {
                r3 = x.getPlain()
                if (r3 == 0) x.setPlain(42)
            }
            val t1 = thread {
                t3.join()
                r1 = x.getPlain()
                y.setPlain(r1)
            }
            val t2 = thread {
                r2 = y.getPlain()
                x.setPlain(r2)
            }
            t1.join()
            t2.join()
            t3.join()
            listOf(r1, r2, r3)
        }
    }
}

private fun causalityWitness(
    number: Int,
    catalogueAllows: Boolean,
    witness: List<Int>,
    modelForbids: Boolean = false,
): OutcomeVerifier<List<Int>> = OutcomeVerifier { outcomes ->
    assertTrue("No completed execution for causality case $number", outcomes.isNotEmpty())
    val observed = witness in outcomes
    println("CAUSALITY_WITNESS case=$number catalogueAllows=$catalogueAllows observed=$observed witness=$witness")
    // Retain the former Evaluation regression independently of the catalogue/JLS04 judgment.
    if (!catalogueAllows || modelForbids) {
        assertNever(setOf(witness)).verify(outcomes)
    }
}
