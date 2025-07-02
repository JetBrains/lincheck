/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.tracedata

import org.jetbrains.kotlinx.lincheck.util.isPrimitive
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.math.max
import kotlin.math.min

internal abstract class SeekableInputStream: InputStream() {
    private var mark: Long = 0

    override fun skip(n: Long): Long {
        val oldPos = position()
        seek(max(0L, oldPos + n))
        return position() - oldPos
    }

    override fun mark(readlimit: Int) {
        mark = position()
    }

    override fun reset() {
        seek(mark)
    }

    override fun markSupported(): Boolean = true

    abstract fun seek(pos: Long)
    abstract fun position(): Long
}

internal class SeekableChannelBufferedInputStream(
    private val channel: SeekableByteChannel
): SeekableInputStream() {
    private val buffer = ByteBuffer.allocate(1048576)
    private var mark: Long = 0
    private var bufferStartPosition: Long = 0

    init {
        // Mark it as "empty"
        buffer.position(buffer.capacity())
    }

    override fun read(): Int {
        if (buffer.remaining() < 1) {
            if (!refillBuffer()) {
                return -1
            }
        }
        return buffer.get().toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (buffer.remaining() < 1) {
            if (!refillBuffer()) {
                return -1
            }
        }
        val toRead = min(buffer.remaining(), len)
        buffer.get(b, off, toRead)
        return toRead
    }

    override fun available(): Int {
        return (channel.size() - position()).toInt()
    }

    override fun close() {
        channel.close()
    }

    override fun seek(pos: Long) {
        if (pos < 0) throw IllegalArgumentException("Pos should be non-negative, but was $pos")

        if (pos == bufferStartPosition + buffer.position()) return

        if (pos < bufferStartPosition || pos >= bufferStartPosition + buffer.capacity()) {
            buffer.clear()
            // Mark as empty for reading
            buffer.position(buffer.capacity())
            channel.position(pos)
        } else {
            buffer.position((pos - bufferStartPosition).toInt())
        }
    }

    override fun position(): Long {
        return bufferStartPosition + buffer.position()
    }

    private fun refillBuffer(): Boolean {
        buffer.clear()
        bufferStartPosition = channel.position()
        var read = 0
        while (read == 0) {
            read = channel.read(buffer)
            if (read < 0) return false
        }
        buffer.rewind()
        return true
    }
}

// Chunk is (from, size)
internal class SeekableChunkedInputStream(
    private val input: SeekableInputStream,
    achunks: List<Pair<Long, Long>>
): SeekableInputStream() {
    private val chunks = achunks.toMutableList()
    private val accumulatedSizes = mutableListOf<Long>()

    private var chunk: Int = 0
    private var positionInChunk: Long = 0L
    private var buf = ByteArray(1)

    init {
        var aSize = 0L
        for (chunk in chunks) {
            accumulatedSizes.add(aSize)
            aSize += chunk.second
        }
        accumulatedSizes.add(aSize)
    }

    override fun read(): Int {
        val read = read(buf, 0, 1)
        if (read < 0) return read
        return buf[0].toInt() and 0xff
    }

    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        val ch = chunks.getOrNull(chunk) ?: return -1
        val toRead = min(len, (ch.second - positionInChunk).toInt())

        input.seek(ch.first + positionInChunk)
        val read = input.read(b, off, toRead)

        positionInChunk += read
        if (positionInChunk == ch.second) {
            positionInChunk = 0
            chunk++
        }
        return read
    }

    override fun available(): Int = (accumulatedSizes.last() - accumulatedSizes[chunk] - positionInChunk).toInt()

    override fun seek(pos: Long) {
        if (pos < 0) throw IllegalArgumentException("Pos should be non-negative, but was $pos")
        if (chunks.isEmpty()) return

        if (pos >= accumulatedSizes.last()) {
            chunk = chunks.size
            positionInChunk = 0
            return
        }

        val idx = accumulatedSizes.binarySearch(pos)
        // Exact match points to exact chunk
        if (idx >= 0) {
            chunk = idx
            positionInChunk = 0
        } else {
            // -insertion_point - 1 -> -(insertion_point + 1) and it points *next after* which we need
            chunk = -idx - 2
            positionInChunk = pos - accumulatedSizes[chunk]
        }
        // Don't seek underlying stream as we re-seek on each read
    }

    override fun position(): Long = accumulatedSizes[chunk] + positionInChunk

    fun appendChunk(offset: Long, size: Long) {
        chunks.add(offset to size)
        accumulatedSizes.add(accumulatedSizes.last() + size)
    }
}

internal class SeekableDataInput (
    private val stream: SeekableInputStream
) : DataInputStream(stream) {
    fun seek(pos: Long) {
        stream.seek(pos)
    }

    fun position(): Long {
        return stream.position()
    }
}
