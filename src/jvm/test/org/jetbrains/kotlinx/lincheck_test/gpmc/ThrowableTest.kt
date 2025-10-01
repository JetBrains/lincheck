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

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent.ensureClassHierarchyIsTransformed
import kotlin.concurrent.thread
import org.junit.Test

class ThrowableTest {

    @Test(timeout = TIMEOUT)
    fun throwableTest() = Lincheck.runConcurrentTest(invocations = 10_000) {
        // `Throwable::addSuppressed` uses `ArrayList` internally to store suppressed exceptions.
        // We need to ensure it is instrumented to trigger deadlock (see below).
        ensureClassHierarchyIsTransformed(java.util.ArrayList::class.java)

        val cause = Throwable("Cause")
        val suppressed = Throwable("Suppressed")
        val throwable = Throwable("Throwable", cause)

        // `Throwable::addSuppressed` and `Throwable::cause` are both `synchronized` methods,
        // meaning they acquire the monitor before executing the method body.
        // We call them concurrently in an attempt to trigger deadlock.
        //
        // Without additional measures, the deadlock can happen in the following way:
        // - thread 1 will acquire monitor on `addSuppressed`;
        // - internally `addSuppressed` will call `ArrayList::add` method;
        // - as this method is instrumented, it will call one of Lincheck injections;
        // - this injection can decide to block the thread and switch to thread 2;
        // - thread 2 will attempt to acquire monitor on `cause`;
        // - this will deadlock with thread 1.
        //
        // To prevent this deadlock, Lincheck should ensure that the ` Throwable ` class is instrumented,
        // and thus its monitor events are intercepted and could be handled by thread scheduler.
        val t1 = thread {
            throwable.addSuppressed(suppressed)
        }
        val t2 = thread {
            throwable.cause?.also {
                it.addSuppressed(suppressed) // do something
            }
        }

        t1.join()
        t2.join()
    }

    companion object {
        @JvmField var throwable: Throwable? = null
        @JvmField var counter: Int = 0
    }

}