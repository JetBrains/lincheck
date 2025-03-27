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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Here we aim to test the problem with jit optimizing out calls
 * to local object manager. Which used to cause invalid thread switch attempt.
 * The issues used to easily reproduce on old jdks (e.g. 8, 11).
 *
 * See [Invalid thread switch attempt](https://github.com/JetBrains/lincheck/issues/576).
 */
@OptIn(ExperimentalModelCheckingAPI::class)
class IntrinsicCandidateMethodTest {

    @Test
    fun testArraysCopyOf() {
        runConcurrentTest(15000) {
            val threads = mutableListOf<Thread>()
            val a = AtomicInteger(0)

            // `ArrayList::copyOf` method is called internally (on array resize)
            // which is annotated with @[HotSpot]IntrinsicCandidate. Method's body is substituted
            // with predefined assembly, thus, execution was non-deterministic, because the
            // substitution overrides lincheck's instrumentation unexpectedly.
            // Operation inside each thread does not really matter, but with some operations it harder to trigger jit.
            threads += thread {
                a.incrementAndGet()
            }

            threads += thread {
                a.incrementAndGet()
            }

            a.incrementAndGet()
            for (t in threads) {
                t.join()
            }
            check(a.get() == 3)
        }
    }
}