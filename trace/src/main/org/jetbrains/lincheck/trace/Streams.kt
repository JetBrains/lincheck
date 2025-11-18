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

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

        if (pos < bufferStartPosition || pos >= bufferStartPosition + buffer.limit()) {
            // It will trigger re-fill on next read(), as remaining() will return 0.
            buffer.limit(0)
            // Refill will start from the given position
            bufferStartPosition = pos
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
        // Set limit = position, position = 0,
        buffer.flip()
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

/**
 * DataOutputStream wrapper from JDK is unbelievably slow on old JDK versions (8, 11 at least).
 *
 * Implement `DataOutput` here directly to `ByteBuffer` gives huge boost to performance.
 */
internal class ByteBufferOutputStream(
    private val bufferSize: Int
) : OutputStream(), DataOutput {
    // DataOutput uses big endian order
    private var buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)

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

    override fun writeBoolean(v: Boolean) {
        if (buffer.remaining() < Byte.SIZE_BYTES) {
            throw BufferOverflowException()
        }
        buffer.put(if(v) 1 else 0)
    }

    override fun writeByte(v: Int) {
        if (buffer.remaining() < Byte.SIZE_BYTES) {
            throw BufferOverflowException()
        }
        buffer.put(v.toByte())
    }

    override fun writeShort(v: Int) {
        if (buffer.remaining() < Short.SIZE_BYTES) {
            throw BufferOverflowException()
        }
        buffer.putShort(v.toShort())
    }

    override fun writeChar(v: Int) {
        if (buffer.remaining() < Char.SIZE_BYTES) {
            throw BufferOverflowException()
        }
        buffer.putChar(v.toChar())
    }

    override fun writeInt(v: Int) {
        if (buffer.remaining() < Int.SIZE_BYTES) {
            throw BufferOverflowException()
        }
        buffer.putInt(v)
    }

    override fun writeLong(v: Long) {
        if (buffer.remaining() < Long.SIZE_BYTES) {
            throw BufferOverflowException()
        }
        buffer.putLong(v)
    }

    override fun writeFloat(v: Float) {
        if (buffer.remaining() < Float.SIZE_BYTES) {
            throw BufferOverflowException()
        }
        buffer.putFloat(v)
    }

    override fun writeDouble(v: Double) {
        if (buffer.remaining() < Double.SIZE_BYTES) {
            throw BufferOverflowException()
        }
        buffer.putDouble(v)
    }

    override fun writeBytes(s: String) {
        if (buffer.remaining() < s.length) {
            throw BufferOverflowException()
        }
        s.forEach {
            buffer.put(it.code.toByte())
        }
    }

    override fun writeChars(s: String) {
        if (buffer.remaining() < s.length * Char.SIZE_BYTES) {
            throw BufferOverflowException()
        }
        s.forEach {
            buffer.putChar(it)
        }
    }

    override fun writeUTF(s: String) {
        val strlen = s.length
        var utflen = strlen // optimized for ASCII

        repeat(strlen) { i ->
            val c = s[i].code
            if (c >= 0x80 || c == 0) utflen += if (c >= 0x800) 2 else 1
        }

        require(utflen < 65536 && utflen >= strlen) { "Invalid input string" }
        if (buffer.remaining() < 2 + utflen) {
            throw BufferOverflowException()
        }

        buffer.putShort(utflen.toShort())

        repeat(strlen) { i ->
            val c: Int = s[i].code
            if (c < 0x80 && c != 0) {
                buffer.put(c.toByte())
            } else if (c >= 0x800) {
                buffer.put((0xE0 or ((c shr 12) and 0x0F)).toByte())
                buffer.put((0x80 or ((c shr 6) and 0x3F)).toByte())
                buffer.put((0x80 or ((c shr 0) and 0x3F)).toByte())
            } else {
                buffer.put((0xC0 or ((c shr 6) and 0x1F)).toByte())
                buffer.put((0x80 or ((c shr 0) and 0x3F)).toByte())
            }
        }
    }

    fun available(): Int = buffer.remaining()

    fun detachBuffer(): ByteBuffer {
        val current = buffer
        buffer = ByteBuffer.allocate(bufferSize)
        current.flip()
        return current
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
