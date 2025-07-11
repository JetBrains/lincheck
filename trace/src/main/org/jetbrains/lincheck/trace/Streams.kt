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

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
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

internal class BufferOverflowException: Exception()

internal class ByteBufferOutputStream(
    bufferSize: Int
) : OutputStream() {
    private val buffer = ByteBuffer.allocate(bufferSize)

    override fun write(b: Int) {
        if (buffer.remaining() < 1) {
            throw BufferOverflowException()
        }
        buffer.put(b.toByte())
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (buffer.remaining() < len) {
            throw BufferOverflowException()
        }
        buffer.put(b, off, len)
    }

    fun available(): Int = buffer.remaining()

    fun getBuffer(): ByteBuffer {
        buffer.flip()
        return buffer
    }

    fun reset() {
        buffer.clear()
    }

    fun mark() {
        buffer.mark()
    }

    fun rollback() {
        buffer.reset()
    }

    fun position(): Int = buffer.position()
}

internal class PositionCalculatingOutputStream(
    private val out: OutputStream
) : OutputStream() {
    private var position: Long = 0

    val currentPosition: Long get() = position

    override fun write(b: Int) {
        out.write(b)
        position += 1
    }

    override fun write(b: ByteArray) {
        out.write(b)
        position += b.size
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        position += len
    }

    override fun flush(): Unit = out.flush()

    override fun close(): Unit = out.close()
}
