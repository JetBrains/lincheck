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

internal sealed class AddressIndex {
    var size: Int = 0
        protected set

    abstract operator fun get(index: Int): Long

    abstract fun add(address: Long): AddressIndex
    open fun finishWrite() {}

    internal companion object {
        fun create(): AddressIndex = NullAddressIndex()
    }
}

private class NullAddressIndex: AddressIndex() {
    override fun get(index: Int): Long = -1L

    override fun add(address: Long): AddressIndex {
        if (address < 0) {
            size++
            return this
        }
        var replacement: AddressIndex = MemoryAddressIndex()
        repeat(size) {
            replacement = replacement.add(-1)
        }
        return replacement.add(address)
    }
}

// 1 MiB of memory, max
private const val MAX_MEM_INDEX_SIZE = 1024 * 1024 / Long.SIZE_BYTES

private class MemoryAddressIndex: AddressIndex() {
    private var storage: LongArray = LongArray(16)

    override fun get(index: Int): Long {
        if (index !in 0..<size) throw IndexOutOfBoundsException("Index: $index, Size: $size")
        return storage.get(index)
    }

    override fun add(address: Long): AddressIndex {
        if (address < 0) return this

        if (size == MAX_MEM_INDEX_SIZE) {
            val fileIndex = MemoryMapAddressIndex(this.storage, size)
            fileIndex.add(address)
            return fileIndex
        }
        if (size == storage.size) {
            storage = storage.copyOf(min(MAX_MEM_INDEX_SIZE, size * 2))
        }
        storage[size] = address
        size++
        return this
    }

}

private const val LONG_SIZE_SHIFT = 3 // log2(Long.SIZE_BYTES)
private const val MMAP_SEGMENT_SIZE = MAX_MEM_INDEX_SIZE * Long.SIZE_BYTES

private class MemoryMapAddressIndex(
    copyFrom: LongArray,
    count: Int
) : AddressIndex() {
    private val storage: MemMapTemporaryStorage
    private var writeFinished = false

    init {
        val tmp = Files.createTempFile("trace-recorder-method-call-index", ".idx")
        storage = MemMapTemporaryStorage(tmp, MMAP_SEGMENT_SIZE)

        val seg = storage.prepareSegment(0)
        check(seg.capacity() >= count * Long.SIZE_BYTES) { "Internal error: wrong buffer size" }
        seg.asLongBuffer().put(copyFrom, 0, count)

        size = count
    }

    override fun get(index: Int): Long {
        check(writeFinished) { "Cannot read from unfinished index" }
        require(index in 0 ..< size) { "Index: $index, Size: $size" }

        val offset = index.toLong() shl LONG_SIZE_SHIFT
        val segment = storage.getSegment(offset) ?: return -1
        val segOff = storage.getOffsetInSegment(offset)

        return segment.getLong(segOff)
    }

    override fun add(address: Long): AddressIndex {
        check(!writeFinished) { "Cannot write to finished index" }

        val offset = size.toLong() shl LONG_SIZE_SHIFT
        val segment = storage.prepareSegment(offset)
        val segOff = storage.getOffsetInSegment(offset)
        segment.putLong(segOff, address)
        size++

        return this
    }

    override fun finishWrite() {
        if (writeFinished) return
        writeFinished = true
    }
}
