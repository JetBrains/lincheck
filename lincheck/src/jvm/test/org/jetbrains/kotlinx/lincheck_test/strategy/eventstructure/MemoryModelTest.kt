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
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.junit.Test
import kotlin.concurrent.thread

/**
 * These tests check that [EventStructureStrategy] adheres to the weak memory model.
 * It contains various litmus tests to check for specific weak behaviors.
 */
class MemoryModelTest {

    @Test
    fun testRRWW() {
        val outcomes: Set<Pair<Int, Int>> = setOf(
            (0 to 0),
            (0 to 1),
            (1 to 0),
            (1 to 1)
        )
        litmustTestv2(outcomes) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0;
            var r2 = 0;
            val t1 = thread { r1 = x.get(); r2 = y.get() }
            val t2 = thread { y.set(1) }
            val t3 = thread { x.set(1) }
            t1.join()
            t2.join()
            t3.join()
            (r1 to r2)
        }
    }

    /* ======== Store Buffering ======== */

    @Test
    fun testSB() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(
//            (0 to 0),
            (0 to 1),
            (1 to 0),
            (1 to 1),
        )
        litmustTestv2(expectedOutcomes) {
            val x = AtomicInteger(0)
            val y = AtomicInteger(0)
            var r1 = 0;
            var r2 = 0;
            val t1 = thread { x.set(1); r1 = y.get()  }
            val t2 = thread { y.set(1); r2 = x.get()  }
            t1.join()
            t2.join()
            (r1 to r2)
        }
    }

    // New version
    @Test
    fun testRRWOpaque() {
        val expectedOutcomes: Set<Pair<Int, Int>> = setOf(
            (0 to 0),
            (0 to 1),
            (1 to 0),
//            (1 to 1), TODO: fix exploration strat to unlock this outcome
        )
        litmustTestv2(expectedOutcomes) {
            val x = AtomicInteger(0)
            var r1 = 0;
            var r2 = 0;
            val t1 = thread { r1 = x.getOpaque() }
            val t2 = thread { r2 = x.getOpaque() }
            val t3 = thread { x.setOpaque(1) }
            t1.join()
            t2.join()
            t3.join()
            (r1 to r2)
        }
    }
}