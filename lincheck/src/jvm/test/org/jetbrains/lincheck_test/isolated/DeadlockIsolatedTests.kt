/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.isolated

import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck_test.AbstractLincheckTest
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

    override fun LincheckSettings.customize() {
        // TODO: We ignore this test with EventStructure for now, as we need to actually report the deadlock, instead of crashing
        ignoreExperimentalModelChecking()
        scenarioGeneration { minimizeFailedScenario = false }
        invocationTimeoutMs = 200
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

    override fun LincheckSettings.customize() {
        // TODO: We ignore this test with EventStructure for now, as we need to actually report the deadlock, instead of crashing
        ignoreExperimentalModelChecking()
        scenarioGeneration {
            operationsBefore = 0
            minimizeFailedScenario = false
        }
        invocationTimeoutMs = 200
    }
}

class LiveLockIsolatedTest : AbstractLincheckTest(TimeoutFailure::class, ManagedLivelockFailure::class) {
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

    override fun LincheckSettings.customize() {
        // TODO: We ignore this test with EventStructure for now, as we need to turn on the loop detector
        ignoreExperimentalModelChecking()
        scenarioGeneration { minimizeFailedScenario = false }
        invocationTimeoutMs = 200
    }

    private fun AtomicBoolean.withSpinLock(block: () -> Int): Int {
        while (!this.compareAndSet(false, true));
        val result = block()
        this.set(false)
        return result
    }
}

