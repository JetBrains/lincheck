/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Test
import java.util.concurrent.Executors

/**
 * This test aims to check that last invocation in the `ManagedStrategy`
 * is completed fully with all spin-cycle replays.
 * For more details see issue https://github.com/JetBrains/lincheck/issues/590.
 */
@OptIn(ExperimentalModelCheckingAPI::class)
class ManySpinCycleReplaysWithLittleInvocations {

    @Test(expected = LincheckAssertionError::class)
    fun test() {
        runConcurrentTest(1) {
            val pool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
            runBlocking(pool) { /* do nothing */ }
            pool.close()
            check(false)
        }
    }
}