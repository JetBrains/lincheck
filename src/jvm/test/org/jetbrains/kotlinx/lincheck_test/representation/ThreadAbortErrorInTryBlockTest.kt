/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.*
import org.junit.Assert.*

class ThreadAbortErrorInTryBlockTest {
    private val threadsIn = atomic(0)

    @Operation
    fun operation() = try {
        threadsIn.incrementAndGet()
        // Ensure that both threads are in this while upon deadlock,
        // so that finally block will be executed in the second thread.
        while (threadsIn.value == 2);
    } finally {
        threadsIn.decrementAndGet()
    }

    @Test
    fun test() {
        val options = ModelCheckingOptions()
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
            .threads(2)
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val threadAbortedErrorName = LincheckAnalysisAbortedError::class.simpleName!!
        check(failure is ManagedDeadlockFailure) {
            "$threadAbortedErrorName overrode deadlock because of try-finally"
        }
        val log = StringBuilder().appendFailure(failure).toString()
        check(threadAbortedErrorName !in log) {
            "$threadAbortedErrorName was logged"
        }
        assertTrue("Lincheck internal events in output must not be present",
                   "org.jetbrains.kotlinx.lincheck." !in log)
    }
}