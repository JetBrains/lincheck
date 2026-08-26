/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure

import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// Rmw tests that do not work in JDK8
class AtomicsTestsJDK11 {

    @Test
    fun testCAS2() {
        val outcomes = setOf<List<Int>>(
            listOf(1,0),
            listOf(0,1)
        )
        litmusTest(assertSame(outcomes)) {
            val x = AtomicInteger(0)
            val r = IntArray(2)

            val t1 = thread {
                r[0] = if (x.weakCompareAndSetPlain(0, 3)) 1 else 0
                if (r[0] == 0) r[1] = x.getAndIncrement()
            }

            val t2 = thread {
                x.setOpaque(1)
            }

            t1.join()
            t2.join()

            listOf(r[0], r[1])
        }
    }
}