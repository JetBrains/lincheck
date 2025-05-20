/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.guide

import kotlin.concurrent.thread
import org.jetbrains.lincheck.Lincheck
import junit.framework.TestCase.assertEquals
import org.junit.Test


class CounterTest {
    // @Test // TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun test() = Lincheck.runConcurrentTest {
        var counter = 0
        // Increment the counter concurrently
        val t1 = thread { counter++ }
        val t2 = thread { counter++ }
        // Wait for the thread completions
        t1.join()
        t2.join()
        // Check both increments have been applied
        assertEquals(2, counter)
    }
}