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

import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Here we aim to test the problem with jit optimizing out calls
 * to local object manager. Which used to cause invalid thread switch attempt.
 *
 * See [Invalid thread switch attempt](https://github.com/JetBrains/lincheck/issues/576).
 */
@OptIn(ExperimentalModelCheckingAPI::class)
class IntrinsicCandidateMethodTest {

    @Test
    fun testArraysCopyOf() {
        runConcurrentTest(10000) {
            val threads = mutableListOf<Thread>()
            val l = CountDownLatch(1)

            // `ArrayList::copyOf` method is called internally (on array resize)
            // which is annotated with @[HotSpot]IntrinsicCandidate. Method's body is substituted
            // with predefined assembly, thus, execution was non-deterministic, because the
            // substitution overrides lincheck's instrumentation unexpectedly.
            threads += thread {
                l.await()
            }

            threads += thread {
                l.await()
            }

            l.countDown()
            for (t in threads) {
                t.join()
            }
        }
    }
}