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

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.DELETE_ON_CLOSE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import kotlin.math.min
import kotlin.math.roundToInt

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

internal class NullAddressIndex: AddressIndex() {
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
private const val MAX_MEM_INDEX_SIZE = 131072

internal class MemoryAddressIndex: AddressIndex() {
    private var storage: LongArray = LongArray(16)

    override fun get(index: Int): Long {
        if (index !in 0..<size) throw IndexOutOfBoundsException("Index: $index, Size: $size")
        return storage.get(index)
    }

    override fun add(address: Long): AddressIndex {
        if (address < 0) return this

        if (size == MAX_MEM_INDEX_SIZE) {
            val fileIndex = FileAddressIndex(this.storage, size)
            fileIndex.add(address)
            return fileIndex
        }
        if (size == storage.size) {
            storage = storage.copyOf(min(MAX_MEM_INDEX_SIZE, (size * 1.25).roundToInt()))
        }
        storage[size] = address
        size++
        return this
    }

}

private const val LONG_SIZE_SHIFT = 3 // log2(Long.SIZE_BYTES)

internal class FileAddressIndex(
    copyFrom: LongArray,
    count: Int
) : AddressIndex() {
    private val storage: SeekableByteChannel

    private var bufferStart: Int = 0
    private var bufferCount: Int = 0
    private val buffer: ByteBuffer = ByteBuffer.allocate(MAX_MEM_INDEX_SIZE shl LONG_SIZE_SHIFT)
    private var writeFinished = false

    init {
        val tmp = Files.createTempFile("trace-recorder-method-call-index", ".idx")
        storage = Files.newByteChannel(tmp, TRUNCATE_EXISTING, DELETE_ON_CLOSE, READ, WRITE)

        val prestored = ByteBuffer.allocate(count * Long.SIZE_BYTES)
        prestored.asLongBuffer().put(copyFrom, 0, count)
        storage.write(prestored)

        bufferStart = count
        size = count
    }

    override fun get(index: Int): Long {
        check(writeFinished) { "Cannot read from unfinished index" }
        require(index in 0 ..< size) { "Index: $index, Size: $size" }

        if (index in bufferStart ..< bufferStart + bufferCount) {
            return buffer.getLong((index - bufferStart) shl LONG_SIZE_SHIFT)
        }

        buffer.clear()
        bufferStart = index
        storage.position(bufferStart.toLong() shl LONG_SIZE_SHIFT)
        storage.read(buffer)
        bufferCount = buffer.position() shr LONG_SIZE_SHIFT
        buffer.flip()
        return buffer.getLong(0)
    }

    override fun add(address: Long): AddressIndex {
        check(!writeFinished) { "Cannot write to finished index" }
        if (buffer.remaining() == 0) {
            buffer.flip()
            // We don't need seek, as nothing could move position from last write
            storage.write(buffer)
            bufferStart = size
            buffer.clear()
        }
        buffer.putLong(address)
        size++
        return this
    }

    override fun finishWrite() {
        if (writeFinished) return
        buffer.flip()
        storage.write(buffer)
        buffer.clear()
        bufferStart = 0
        bufferCount = 0
        writeFinished = true
    }
}
