/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import java.util.concurrent.atomic.*

class AtomicCompareAndExchangeRepresentationTest : BaseTraceRepresentationTest("atomic_compare_and_exchange_trace") {
    private val integer = AtomicInteger(0)
    private val long = AtomicLong(0)
    private val boolean = AtomicBoolean(false)
    private val reference = AtomicReference("initial")
    private val integers = AtomicIntegerArray(2)
    private val longs = AtomicLongArray(2)
    private val references = AtomicReferenceArray(arrayOf("initial", "initial"))

    override fun operation() {
        integer.compareAndExchange(0, 1)
        integer.compareAndExchangeAcquire(0, 2)
        integer.compareAndExchangeRelease(1, 2)

        long.compareAndExchange(0, 1)
        long.compareAndExchangeAcquire(0, 2)
        long.compareAndExchangeRelease(1, 2)

        boolean.compareAndExchange(false, true)
        boolean.compareAndExchangeAcquire(false, false)
        boolean.compareAndExchangeRelease(true, false)

        reference.compareAndExchange("initial", "next")
        reference.compareAndExchangeAcquire("initial", "other")
        reference.compareAndExchangeRelease("next", "done")

        integers.compareAndExchange(1, 0, 1)
        integers.compareAndExchangeAcquire(1, 0, 2)
        integers.compareAndExchangeRelease(1, 1, 2)

        longs.compareAndExchange(1, 0, 1)
        longs.compareAndExchangeAcquire(1, 0, 2)
        longs.compareAndExchangeRelease(1, 1, 2)

        references.compareAndExchange(1, "initial", "next")
        references.compareAndExchangeAcquire(1, "initial", "other")
        references.compareAndExchangeRelease(1, "next", "done")
    }
}
