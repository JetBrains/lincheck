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
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.DELETE_ON_CLOSE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import kotlin.math.min
import kotlin.math.roundToInt

internal sealed class AddressIndex {
    var size: Int = 0
        protected set

    abstract operator fun get(index: Int): Long
    abstract fun add(address: Long): AddressIndex

    internal companion object {
        fun create(): AddressIndex = MemoryAddressIndex()
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

private const val READ_BUFFER_SIZE = MAX_MEM_INDEX_SIZE / 2
private const val WRITE_BUFFER_SIZE = MAX_MEM_INDEX_SIZE / 2

internal class FileAddressIndex(
    copyFrom: LongArray,
    count: Int
) : AddressIndex() {
    private val storage: SeekableByteChannel

    private var writeBufferStart: Int = 0
    private val writeBuffer: ByteBuffer = ByteBuffer.allocate(WRITE_BUFFER_SIZE * Long.SIZE_BYTES)

    private var readBufferStart: Int = 0
    private var readBufferCount: Int = 0
    private val readBuffer: ByteBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE * Long.SIZE_BYTES)

    init {
        val tmp = Files.createTempFile("trace-recorder-method-call-index", "idx")
        storage = Files.newByteChannel(tmp, CREATE_NEW, DELETE_ON_CLOSE, READ, WRITE)

        val prestored = ByteBuffer.allocate(count * Long.SIZE_BYTES)
        prestored.asLongBuffer().put(copyFrom, 0, count)
        storage.write(prestored)

        writeBufferStart = count
        size = count
    }

    override fun get(index: Int): Long {
        require(index in 0 ..< size) { "Index: $index, Size: $size" }
        if (index >= writeBufferStart) {
            return writeBuffer.getLong(index - writeBufferStart)
        }

        if (index in readBufferStart ..< readBufferStart + readBufferCount) {
            return readBuffer.getLong(index - readBufferStart)
        }

        readBuffer.clear()
        readBufferStart = index * Long.SIZE_BYTES
        storage.position(readBufferStart.toLong())
        storage.read(readBuffer)
        readBufferCount = readBuffer.position() / Long.SIZE_BYTES
        readBuffer.flip()
        return readBuffer.getLong(0)
    }

    override fun add(address: Long): AddressIndex {
        if (writeBuffer.remaining() == 0) {
            writeBuffer.flip()
            storage.position((writeBufferStart * Long.SIZE_BYTES).toLong())
            storage.write(writeBuffer)
            writeBufferStart = size
            writeBuffer.clear()
        }
        writeBuffer.putLong(address)
        size++
        return this
    }
}
