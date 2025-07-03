/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.isolated

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import org.jetbrains.lincheck.datastructures.Options
import java.util.concurrent.atomic.AtomicBoolean

class DeadlockOnSynchronizedIsolatedTest : AbstractLincheckTest(TimeoutFailure::class, ManagedDeadlockFailure::class) {
    private var counter = 0
    private var lock1 = Any()
    private var lock2 = Any()

    @Operation
    fun inc12(): Int {
        synchronized(lock1) {
            synchronized(lock2) {
                return counter++
            }
        }
    }

    @Operation
    fun inc21(): Int {
        synchronized(lock2) {
            synchronized(lock1) {
                return counter++
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        minimizeFailedScenario(false)
        invocationTimeout(200)
    }
}

class DeadlockOnSynchronizedWaitIsolatedTest : AbstractLincheckTest(TimeoutFailure::class, ManagedDeadlockFailure::class) {
    private var lock = Object()

    @Operation
    fun operation() {
        synchronized(lock) {
            lock.wait()
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
        minimizeFailedScenario(false)
        invocationTimeout(200)
    }
}

class LiveLockIsolatedTest : AbstractLincheckTest(TimeoutFailure::class, ManagedDeadlockFailure::class) {
    private var counter = 0
    private val lock1 = AtomicBoolean(false)
    private val lock2 = AtomicBoolean(false)

    @Operation
    fun inc12(): Int = lock1.withSpinLock {
            lock2.withSpinLock {
                counter++
            }
        }

    @Operation
    fun inc21(): Int = lock2.withSpinLock {
            lock1.withSpinLock {
                counter++
            }
        }

    override fun <O : Options<O, *>> O.customize() {
        minimizeFailedScenario(false)
        invocationTimeout(200)
    }

    private fun AtomicBoolean.withSpinLock(block: () -> Int): Int {
        while (!this.compareAndSet(false, true));
        val result = block()
        this.set(false)
        return result
    }
}

