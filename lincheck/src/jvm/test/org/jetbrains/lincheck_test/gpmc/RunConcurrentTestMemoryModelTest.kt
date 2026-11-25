/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.gpmc

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Checks the memory model selected through the public [runConcurrentTest] overload end-to-end:
 * a plain store-buffering outcome is reachable under [MemoryModel.RelaxedJava] and not under
 * [MemoryModel.SequentialConsistency], and reaching it is reported as a [LincheckAssertionError].
 */
class RunConcurrentTestMemoryModelTest {

    @Test(expected = LincheckAssertionError::class)
    fun `relaxed model finds plain store buffering`() =
        runConcurrentTest(memoryModel = MemoryModel.RelaxedJava) {
            var x = 0
            var y = 0
            var r1 = -1
            var r2 = -1
            val t1 = thread {
                x = 1
                r1 = y
            }
            val t2 = thread {
                y = 1
                r2 = x
            }
            t1.join()
            t2.join()
            // Both reads observing the initial value is a store-buffering outcome,
            // which sequential consistency forbids.
            assert(!(r1 == 0 && r2 == 0))
        }

    @Test
    fun `sequential consistency does not find plain store buffering`() =
        runConcurrentTest(memoryModel = MemoryModel.SequentialConsistency) {
            var x = 0
            var y = 0
            var r1 = -1
            var r2 = -1
            val t1 = thread {
                x = 1
                r1 = y
            }
            val t2 = thread {
                y = 1
                r2 = x
            }
            t1.join()
            t2.join()
            assert(!(r1 == 0 && r2 == 0))
        }
}
