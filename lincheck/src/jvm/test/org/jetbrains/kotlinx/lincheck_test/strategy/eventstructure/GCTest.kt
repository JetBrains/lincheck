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
import kotlin.concurrent.thread

class GCTest {

    @Test
    fun testGC() {
        class Box(val x: Int) {}
        litmusTest(assertSame(setOf(null, 0, 42_000_000), UNKNOWN)) {
            var x: Box? = Box(0)
            var r0 : Int? = -1

            val t1 = thread {
                System.gc()
                System.gc()
                x = Box(42_000_000)
                System.gc()
                System.gc()
            }
            val t2 = thread {
                System.gc()
                System.gc()
                x = null
                System.gc()
                System.gc()
            }
            val t3 = thread {
                System.gc()
                System.gc()
                r0 = x?.x
                System.gc()
                System.gc()
            }

            t1.join()
            t2.join()
            t3.join()
            r0
        }
    }
}