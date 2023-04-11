/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckOptions
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

class WaitNotifyLockTest : VerifierState() {
    private var counter = 0
    private val lock = WaitNotifyLock()

    override fun extractState() = counter

    @Operation
    fun getAndIncrement() = lock.withLock { counter++ }

    @Test
    fun test() {
        LincheckOptions().check(this::class)
    }
}

private class WaitNotifyLock {
    private var owner: Thread? = null

    fun lock() {
        val thread = Thread.currentThread()
        synchronized(this) {
            while (owner != null) {
                (this as Object).wait()
            }
            owner = thread
        }
    }

    fun unlock() {
        synchronized(this) {
            owner = null
            (this as Object).notify()
        }
    }
}

private inline fun <T> WaitNotifyLock.withLock(body: () -> T): T {
    try {
        lock()
        return body()
    } finally {
        unlock()
    }
}