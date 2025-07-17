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

import java.nio.file.Files
import kotlin.math.min
import kotlin.math.roundToInt

internal data class Range(val start: Long, val end: Long)

internal sealed class RangeIndex(
    aOpenRanges: MutableMap<Int, Long>,
) {
    protected val openRanges: MutableMap<Int, Long> = aOpenRanges
    protected var closed = false

    abstract operator fun get(id: Int): Range?
    abstract fun addRange(id: Int, start: Long, end: Long): RangeIndex

    fun addStart(id: Int, start: Long) {
        check(!closed) { "Index already closed." }
        require(id >= 0) { "Id $id must be non-negative" }
        require(start >= 0) { "Id $id: Start $start must be non-negative" }
        check(openRanges[id] == null) { "Id $id must be unique in range index" }
        openRanges[id] = start
    }

    fun setEnd(id: Int, end: Long): RangeIndex {
        check(!closed) { "Index already closed." }
        val start = openRanges.remove(id)
        check(start != null) { "Id $id must have start already" }
        return addRange(id, start, end)
    }

    open fun finishIndex() {
        if (closed) return
        check(openRanges.isEmpty()) { "Index has some non-finished ranges" }
        closed = true
    }

    companion object {
        fun create(): RangeIndex = HashMapRangeIndex()
    }
}


// The size of one element in a hash map is about 36 bytes/entry + object itself is 16, and Range header another 16,
// so, it is like 68 bytes per entry (for 2 longs!), and 2 longs are 16, so lets say 80 bytes.
// Spent no more than 80MByte for all points, so approximately 1,000,000 elements top.

private const val MAX_HASHMAP_SIZE = 1_000_000

private class HashMapRangeIndex: RangeIndex(mutableMapOf()) {
    private val map = mutableMapOf<Int, Range>()

    override operator fun get(id: Int): Range? {
        require(id >= 0) { "Id $id must be non-negative" }
        check(closed) { "Index is not closed properly yet" }
        return map[id]
    }

    override fun addRange(id: Int, start: Long, end: Long): RangeIndex {
        check(!closed) { "Index already closed." }
        require(id >= 0) { "Id $id must be non-negative" }
        require(start >= 0) { "Id $id: Start $start must be non-negative" }
        require(end >= start) { "Id $id: End $end must be larger or equal than start $start" }

        if (map.size >= MAX_HASHMAP_SIZE) {
            val newIndex = MemMapRangeIndex(openRanges)
            map.forEach { (id, range) -> newIndex.addRange(id, range.start, range.end) }
            return newIndex.addRange(id, start, end)
        }

        map[id] = Range(start, end)
        return this
    }
}


// 128MiB segments
private const val MMAP_SEGMENT_SIZE = 128*1024*1024
private const val CELL_SIZE = Long.SIZE_BYTES + Long.SIZE_BYTES
private const val CELL_SHIFT = 4 // log2(CELL_SIZE)

private class MemMapRangeIndex(
    openRanges: MutableMap<Int, Long>,
): RangeIndex(openRanges)
{
    private val storage: MemMapTemporaryStorage

    init {
        val tmp = Files.createTempFile("trace-recorder-method-calls-range-index", ".idx")
        storage = MemMapTemporaryStorage(tmp, MMAP_SEGMENT_SIZE)
    }


    override operator fun get(id: Int): Range? {
        require(id >= 0) { "Id $id must be non-negative" }
        check(closed) { "Index is not closed properly yet" }

        val globOff = id.toLong() shl CELL_SHIFT
        val segment = storage.getSegment(globOff) ?: return null
        val segOff = storage.getOffsetInSegment(globOff)

        return Range(segment.getLong(segOff), segment.getLong(segOff + Long.SIZE_BYTES))
    }

    override fun addRange(id: Int, start: Long, end: Long): RangeIndex {
        check(!closed) { "Index already closed." }
        require(id >= 0) { "Id $id must be non-negative" }
        require(start >= 0) { "Id $id: Start $start must be non-negative" }
        require(end >= start) { "Id $id: End $end must be larger or equal than start $start" }

        val globOff = id.toLong() shl CELL_SHIFT
        val segment = storage.prepareSegment(globOff)
        val segOff = storage.getOffsetInSegment(globOff)

        segment.putLong(segOff, start)
        segment.putLong(segOff + Long.SIZE_BYTES, end)

        return this
    }
}
