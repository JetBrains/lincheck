/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.lincheck.Lincheck
import org.junit.Test
import kotlin.concurrent.thread

class MultipleRootsInRunConcurrent {

    @Test
    fun multipleRootsTest() {
        val result = runCatching {
            Lincheck.runConcurrentTest {
                var counter = 0
                // Increment the counter concurrently
                val t1 = thread { counter++ }
                val t2 = thread { counter++ }
                // Wait for the thread completions
                t1.join()
                t2.join()
                // Check both increments have been applied
                check(counter == 2)
            }
        }
        BaseRunConcurrentRepresentationTest.checkResult(result, "run_concurrent_test/multiple_roots")
    }
}