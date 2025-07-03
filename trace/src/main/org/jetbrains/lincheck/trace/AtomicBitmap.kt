/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

private const val ATOMIC_SIZE_BITS = Long.SIZE_BITS
private const val ATOMIC_SIZE_SHIFT = 6
private const val ATOMIC_BIT_MASK = 0x3F

internal class AtomicBitmap(size: Int) {
    private val bitmap = AtomicReference(AtomicLongArray(size / ATOMIC_SIZE_BITS))

    fun isSet(id: Int): Boolean {
        val idx = id shr ATOMIC_SIZE_SHIFT
        val bit = 1L shl (id and ATOMIC_BIT_MASK)
        return isSet(idx, bit)
    }

    fun set(id: Int) {
        val idx = id shr ATOMIC_SIZE_SHIFT
        val bit = 1L shl (id and ATOMIC_BIT_MASK)

        val b = bitmap.get()
        if (idx >= b.length()) {
            resizeBitmap(idx)
        }
        while (!isSet(idx, bit)) {
            do {
                val b = bitmap.get()
                val oldVal = b.get(idx)
            } while (!b.compareAndSet(idx, oldVal, oldVal or bit))
        }
    }

    private fun isSet(idx: Int, bit: Long): Boolean {
        val b = bitmap.get()
        if (idx >= b.length()) return false
        val value = b.get(idx)
        return (value and bit) != 0L
    }

    private fun resizeBitmap(idx: Int) {
        do {
            val b = bitmap.get()
            // Another thread was faster
            if (b.length() > idx) return

            val newLen = max(idx + 1, b.length() + b.length() / 2)
            val newB = AtomicLongArray(newLen)
            for (i in 0..< b.length()) {
                newB.lazySet(i,b.get(i))
            }
        } while (!bitmap.compareAndSet(b, newB))
    }
}
