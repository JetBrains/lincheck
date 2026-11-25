/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.strategy.eventstructure

import org.junit.Test

/**
 * Regression test for a named `Thread` subclass started and joined through a variable of that
 * subclass type, e.g. `class Worker : Thread(); val t = Worker(); t.start(); t.join()`.
 *
 * `Thread.join()` is `final`, so javac/kotlinc still resolve the call statically, but they emit the
 * call-site owner as the *declared* type of the receiver expression (here, the subclass), not as
 * `java.lang.Thread`. Before the fix, `isThreadJoinCall` only recognized an owner that was exactly
 * `java.lang.Thread`, so this `join()` call was left uninstrumented: the model checker never learned
 * that the thread had to finish before the call returns, which crashed or hung the exploration
 * (`NullPointerException` from `EventStructureStrategy.onThreadStart`, then "Concurrent test has hung").
 * Calling `.start()`/`.join()` through a plain `Thread`-typed variable (e.g. `kotlin.concurrent.thread`)
 * does not reproduce this, because the owner recorded at that call site is already `java.lang.Thread`.
 */
class ThreadSubclassJoinTest {

    class NamedWorkerThread : Thread() {
        @Volatile
        var result: Int = 0

        override fun run() {
            result = 1
        }
    }

    @Test
    fun testNamedThreadSubclassJoin() {
        litmusTest(assertSame(setOf(1)), requireExhaustion = true) {
            val t = NamedWorkerThread()
            t.start()
            t.join()
            t.result
        }
    }
}
