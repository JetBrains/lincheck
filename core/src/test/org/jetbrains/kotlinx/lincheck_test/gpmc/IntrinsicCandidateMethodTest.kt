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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck.Lincheck
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Here we aim to test the problem with jit optimizing out calls
 * to local object manager. Which used to cause invalid thread switch attempt.
 * The issues used to easily reproduce on old jdks (e.g. 8, 11).
 *
 * See [Invalid thread switch attempt](https://github.com/JetBrains/lincheck/issues/576).
 */
class IntrinsicCandidateMethodTest {

    @Test
    fun testArraysCopyOfCall() {
        Lincheck.runConcurrentTest(2000) {
            val threads = mutableListOf<Thread>()
            val l = CountDownLatch(1)

            // `ArrayList::copyOf` method is called internally (on array resize)
            // which is annotated with @[HotSpot]IntrinsicCandidate. Method's body is substituted
            // with predefined assembly, thus, execution was non-deterministic, because the
            // substitution overrides lincheck's instrumentation unexpectedly.
            // Operation inside each thread does not really matter, but with some operations it harder to trigger jit
            // (e.g. with AtomicInteger test sometimes passes).
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

    @Test
    fun testListOfCall() {
        Lincheck.runConcurrentTest(2000) {
            // at least 3 threads required to trigger a bug
            Executors.newFixedThreadPool(3).asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    // second `launch` is required to trigger a bug
                    launch(dispatcher) {}
                    launch(dispatcher) {
                        listOf(1, 2) // at least 2 elements required, otherwise method with no var-args will be called internally
                    }
                }
            }
        }
    }

    @Test
    fun testVarArgsSpread() {
        Lincheck.runConcurrentTest(2000) {
            // at least 3 threads required to trigger a bug
            Executors.newFixedThreadPool(3).asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    // second `launch` is required to trigger a bug
                    launch(dispatcher) {}
                    launch(dispatcher) {
                        acceptVarArgs(1, 2, 3)
                    }
                }
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun acceptVarArgs(vararg args: Any): Array<Any> {
        return arrayOf(*args)
    }
}