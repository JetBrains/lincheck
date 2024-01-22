/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_benchmark.running

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck_benchmark.AbstractLincheckBenchmark
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SpinLockBenchmark : AbstractLincheckBenchmark() {
    private var counter = 0
    private val locked = AtomicBoolean()

    @Operation
    fun increment(): Int = withSpinLock { counter++ }

    @Operation
    fun get(): Int = withSpinLock { counter }

    private fun<T> withSpinLock(block: () -> T): T {
        while (!locked.compareAndSet(false, true)) {
            Thread.yield()
        }
        try {
            return block()
        } finally {
            locked.set(false)
        }
    }
}

class ReentrantLockBenchmark : AbstractLincheckBenchmark() {
    private var counter = 0
    private val lock = ReentrantLock()

    @Operation
    fun increment(): Int = lock.withLock { counter++ }

    @Operation
    fun get(): Int = lock.withLock { counter }
}

class IntrinsicLockBenchmark : AbstractLincheckBenchmark() {
    private var counter = 0

    @Operation
    fun increment(): Int = synchronized(this) { counter++ }

    @Operation
    fun get(): Int = synchronized(this) { counter }
}