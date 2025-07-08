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
import kotlin.math.max

private const val ATOMIC_SIZE_BITS = Long.SIZE_BITS                          // Bits in one atomic array cell (Long)
private const val ATOMIC_SIZE_BYTES = Long.SIZE_BYTES                        // Bytes in one atomic array cell (Long)
private const val ATOMIC_SIZE_SHIFT = 6                                      // log2(ATOMIC_SIZE_BITS)
private const val ATOMIC_BIT_MASK = (1 shl ATOMIC_SIZE_SHIFT)                // Mask to have a reminder for division by ATOMIC_SIZE_BITS
private const val ATOMIC_CHUNK_BYTES = 4096                                  // One chunk in bytes (typical page)
private const val ATOMIC_CHUNK_SIZE = ATOMIC_CHUNK_BYTES / ATOMIC_SIZE_BYTES // One chunk in cells
private const val ATOMIC_CHUNK_BITS = ATOMIC_CHUNK_SIZE * ATOMIC_SIZE_BYTES  // One chunk in bits
private const val ATOMIC_CHUNK_SHIFT = 15                                    // log2(ATOMIC_CHUNK_BITS)
private const val ATOMIC_CHUNK_MASK = (1 shl ATOMIC_CHUNK_SHIFT) - 1         // Mask to have a reminder for division by ATOMIC_CHUNK_BITS

internal class AtomicBitmap {
    private val bitmap = ArrayList<AtomicLongArray>()
    @Volatile
    private var chunkCount = 0

    init {
        // Add the first chunk
        bitmap.add(AtomicLongArray(ATOMIC_CHUNK_SIZE))
        chunkCount = 1
    }

    fun isSet(id: Int): Boolean {
        val chunk = id shr ATOMIC_CHUNK_SHIFT
        // volatile read to see changes by another thread
        if (chunk >= chunkCount) return false

        val bitInChunk = id and ATOMIC_CHUNK_MASK

        val idx = bitInChunk shr ATOMIC_SIZE_SHIFT
        val bit = 1L shl (bitInChunk and ATOMIC_BIT_MASK)

        val ch = bitmap.get(chunk)
        val value = ch.get(idx)
        return (value and bit) != 0L
    }

    fun set(id: Int) {
        val chunk = id shr ATOMIC_CHUNK_SHIFT
        val bitInChunk = id and ATOMIC_CHUNK_MASK

        val idx = bitInChunk shr ATOMIC_SIZE_SHIFT
        val bit = 1L shl (bitInChunk and ATOMIC_BIT_MASK)

        // volatile read of chunkCount
        if (chunk >= chunkCount) {
            resizeBitmap(chunk)
        }

        val ch = bitmap.get(chunk)
        do {
            val value = ch.get(idx)
            val newValue = value or bit
        } while (ch.compareAndSet(idx, value, newValue))
    }
    @Synchronized
    private fun resizeBitmap(chunk: Int) {
        // volatile read of chunkCount
        while (chunk >= bitmap.size) {
            bitmap.add(AtomicLongArray(ATOMIC_CHUNK_SIZE))
        }
        // volatile write for future atomic reads on a hot path
        chunkCount = bitmap.size
    }
}
