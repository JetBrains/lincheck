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

sealed class AddressIndex {
    var size: Int = 0
        protected set

    abstract operator fun get(index: Int): Long
    abstract fun add(address: Long)

    open fun finishWrite() {}

    internal companion object {
        fun create(): AddressIndex = StorageSwitchingAddressIndex()
    }
}

// 1 MiB of memory, max
private const val MAX_MEM_INDEX_SIZE = 1024 * 1024 / Long.SIZE_BYTES

private class StorageSwitchingAddressIndex: AddressIndex() {
    private var storage: AddressIndex? = null

    override fun get(index: Int): Long = storage?.get(index) ?: -1

    override fun add(address: Long) {
        if (storage == null && address < 0) {
            size++
            return
        }

        if (storage == null && size < MAX_MEM_INDEX_SIZE) {
            val newIndex = MemoryAddressIndex()
            repeat(size) {
                newIndex.add(-1)
            }
            storage = newIndex
        } else if (storage == null && size > MAX_MEM_INDEX_SIZE) {
            val newIndex = MemoryMapAddressIndex()
            repeat(size) {
                newIndex.add(-1)
            }
            storage = newIndex
        } else if (storage is MemoryAddressIndex && size >= MAX_MEM_INDEX_SIZE) {
            val newIndex = MemoryMapAddressIndex()
            repeat(size) {
                newIndex.add(storage?.get(it) ?: -1)
            }
            storage = newIndex
        }
        storage?.add(address)
        size++
    }

    override fun finishWrite() {
        storage?.finishWrite()
        super.finishWrite()
    }
}

private class MemoryAddressIndex: AddressIndex() {
    private var storage: LongArray = LongArray(16)

    override fun get(index: Int): Long {
        require(index in 0 ..< size) { "Index: $index, Size: $size" }
        return storage.get(index)
    }

    override fun add(address: Long) {
        if (size == storage.size) {
            storage = storage.copyOf(min(MAX_MEM_INDEX_SIZE, size * 2))
        }
        storage[size++] = address
    }
}

private const val LONG_SIZE_SHIFT = 3 // log2(Long.SIZE_BYTES)
private const val MMAP_SEGMENT_SIZE = MAX_MEM_INDEX_SIZE * Long.SIZE_BYTES

private class MemoryMapAddressIndex: AddressIndex() {
    private val storage: MemMapTemporaryStorage
    private var writeFinished = false

    init {
        val tmp = Files.createTempFile("trace-recorder-method-call-index", ".idx")
        storage = MemMapTemporaryStorage(tmp, MMAP_SEGMENT_SIZE)
    }

    override fun get(index: Int): Long {
        check(writeFinished) { "Cannot read from unfinished index" }
        require(index in 0 ..< size) { "Index: $index, Size: $size" }

        val offset = index.toLong() shl LONG_SIZE_SHIFT
        val segment = storage.getSegment(offset) ?: return -1
        val segOff = storage.getOffsetInSegment(offset)

        return segment.getLong(segOff)
    }

    override fun add(address: Long) {
        check(!writeFinished) { "Cannot write to finished index" }

        val offset = size.toLong() shl LONG_SIZE_SHIFT
        val segment = storage.prepareSegment(offset)
        val segOff = storage.getOffsetInSegment(offset)
        segment.putLong(segOff, address)
        size++
    }

    override fun finishWrite() {
        if (writeFinished) return
        writeFinished = true
    }
}
