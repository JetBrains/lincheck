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

import sun.nio.ch.DirectBuffer
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*

internal class MemMapTemporaryStorage(
    path: Path,
    val segmentSize: Int
): Closeable {
    val mask: Long
    val shift: Int

    private val storage = FileChannel.open(path, TRUNCATE_EXISTING, SPARSE, DELETE_ON_CLOSE, READ, WRITE)
    private val segments = mutableListOf<ByteBuffer?>()

    init {
        check(segmentSize > 2) { "Segment size must be more than 2" }
        check(segmentSize and (segmentSize - 1) == 0) { "Segment size must power of two" }

        shift = 31 - Integer.numberOfLeadingZeros(segmentSize)
        mask = (segmentSize - 1).toLong()
    }

    fun getOffsetInSegment(offset: Long): Int = (offset and mask).toInt()

    fun getSegment(offset: Long): ByteBuffer? {
        val idx = offset shr shift
        return segments.getOrNull(idx.toInt())
    }

    fun prepareSegment(offset: Long): ByteBuffer {
        val idx = (offset shr shift).toInt()
        val seg = segments.getOrNull(idx)
        if (seg != null) return seg

        while (idx >= segments.size) {
            segments.add(null)
        }

        val segmentStart: Long = offset and mask.inv().toLong()
        val afterSize: Long = segmentStart + segmentSize

        if (storage.size() < afterSize) {
            storage.truncate(afterSize)
        }

        val rv = storage.map(FileChannel.MapMode.READ_WRITE, segmentStart, segmentSize.toLong())
        segments[idx] = rv
        return rv
    }

    override fun close() {
        // No way to unmap in standard JVM
        segments.forEach {
            val dbb = it as? DirectBuffer
            dbb?.cleaner()?.clean()
        }
        storage.close()
    }
}