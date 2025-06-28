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

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.math.max
import kotlin.math.min


private const val INPUT_BUFFER_SIZE: Int = 16*1024*1024

/**
 * This needs a file name as it uses a seekable file channel, it is easier than seekable stream
 */
class LazyTraceReader(
    private val dataFileName: String,
    private val index: DataInputStream?,
    private val context: TraceContext
): Closeable {
    constructor(baseFileName: String, context: TraceContext) :
            this(
                dataFileName = baseFileName,
                index = wrapStream(openExistingFile(baseFileName + INDEX_FILENAME_SUFFIX)),
                context = context
            )

    private val data: SeekableDataInput
    private val tracepointPositions = mutableMapOf<Int, Pair<Long, Long>>()

    init {
        val channel = Files.newByteChannel(Path(dataFileName), StandardOpenOption.READ)
        data = SeekableDataInput(SeekableInputStream(channel))

        try {
            // Check format
            val magic = data.readLong()
            if (magic != TRACE_MAGIC) {
                error("TraceRecorder: Wrong magic 0x${(magic.toString(16))} in file $dataFileName, expected ${TRACE_MAGIC.toString(16)}")
            }

            val version = data.readLong()
            if (version != TRACE_VERSION) {
                error("TraceRecorder: Wrong version $version in file $dataFileName, expected $TRACE_VERSION")
            }
        } catch (t : Throwable) {
            data.close()
            index?.close()
            throw t
        }
    }

    override fun close() {
        data.close()
        index?.close()
    }

    fun readRoots(): List<TRTracePoint> {
        loadOrRecreateIndex()

        val roots = mutableListOf<TRTracePoint>()

        val kind = loadObjects(data, context, mutableListOf(), false) { input, context, stringCache ->
            val tracePoint = loadTRTracePoint(input)
            check (tracePoint is TRMethodCallTracePoint) { "Expecting TRMethodCallTracepoint, found ${tracePoint.javaClass.simpleName}" }
            skipChildrenAndLoadFooter(input as SeekableDataInput, tracePoint)
            roots.add(tracePoint)
        }
        check (kind == ObjectKind.EOF) {
            "Input contains additional data: expected EOF get $kind"
        }

        return roots
    }

    fun readChildren(parent: TRMethodCallTracePoint) {
        val positions = tracepointPositions[parent.eventId]
        check(positions != null) { "TRMethodCallTracePoint ${parent.eventId} is not found in index" }
        parent.events.clear()
        data.seek(positions.first)
        val kind = loadObjects(data, context, mutableListOf(), false) { input, context, stringCache ->
            val tracePoint = loadTRTracePoint(input)
            if (tracePoint is TRMethodCallTracePoint) {
                skipChildrenAndLoadFooter(input as SeekableDataInput, tracePoint)
            }
            parent.events.add(tracePoint)
        }
        check (kind == ObjectKind.TRACEPOINT_FOOTER) {
            "Input contains broken data: expected Tracepoint Footer get $kind"
        }
    }

    private fun skipChildrenAndLoadFooter(input: SeekableDataInput, tracePoint: TRMethodCallTracePoint) {
        val positions = tracepointPositions[tracePoint.eventId]
        check(positions != null) { "TRMethodCallTracePoint ${tracePoint.eventId} is not found in index" }
        check(positions.first == data.position()) {
            "TRMethodCallTracePoint ${tracePoint.eventId} has wrong start position in index: ${positions.first}, expected ${data.position()}"
        }
        data.seek(positions.second)
        val kind = input.readKind()
        check(kind == ObjectKind.TRACEPOINT_FOOTER) {
            "Input contains unbalanced method call tracepoint: tracepoint without footer"
        }
        tracePoint.loadFooter(input)
    }

    private fun loadOrRecreateIndex() {
        if (index == null) {
            System.err.println("TraceRecorder: No index file is given for ${this.dataFileName}: read whole data file to re-create index")
            recreateIndex()
        } else if (!loadIndex()) {
            System.err.println("TraceRecorder: Index file for $dataFileName id corrupted: read whole data file to re-create index")
            context.clear()
            recreateIndex()
        }
        // Seek to start after magic and version
        data.seek((Long.SIZE_BYTES * 2).toLong())
    }

    private fun loadIndex(): Boolean {
        if (index == null) return false
        index.use { index ->
            try {
                // Check format
                val magic = index.readLong()
                check (magic == INDEX_MAGIC) {
                    "Wrong index magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}"
                }

                val version = index.readLong()
                check (version == TRACE_VERSION) {
                    "Wrong index version $version, expected $TRACE_VERSION"
                }

                var objNum = 0
                val stringCache = mutableListOf<String?>()
                while (true) {
                    val kind = index.readKind()
                    val id = index.readInt()
                    val start = index.readLong()
                    val end = index.readLong()

                    if (kind == ObjectKind.EOF) break

                    if (kind == ObjectKind.TRACEPOINT) {
                        tracepointPositions[id] = start to end
                    } else {
                        // Check kind
                        data.seek(start)
                        val dataKind = data.readKind()
                        check (dataKind == kind) {
                            "Object $objNum: expected $kind but datafile has $dataKind"
                        }
                        val dataId = when (kind) {
                            ObjectKind.CLASS_DESCRIPTOR -> loadClassDescriptor(data, context, true)
                            ObjectKind.METHOD_DESCRIPTOR -> loadMethodDescriptor(data, context, true)
                            ObjectKind.FIELD_DESCRIPTOR -> loadFieldDescriptor(data, context, true)
                            ObjectKind.VARIABLE_DESCRIPTOR -> loadVariableDescriptor(data, context, true)
                            ObjectKind.STRING -> loadString(data, stringCache, true)
                            ObjectKind.CODE_LOCATION -> loadCodeLocation(data, context, stringCache, true)
                            ObjectKind.TRACEPOINT_FOOTER -> error("Object $objNum is unexpected kind $kind")
                            ObjectKind.TRACEPOINT -> Unit
                            ObjectKind.EOF -> Unit
                        }
                        check (id == dataId) {
                            "Object $objNum of kind $kind: expected $id but datafile has $dataId"
                        }
                    }
                    objNum++
                }
                val magicEnd = index.readLong()
                if (magicEnd != INDEX_MAGIC) {
                    error("Wrong final index magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}")
                }
            } catch (t: Throwable) {
                System.err.println("TraceRecorder: Error reading index for $dataFileName: ${t.message}")
                return false
            }
        }
        return true
    }

    private fun recreateIndex() {
    }

    private companion object {
        fun wrapStream(input: InputStream?): DataInputStream? {
            if (input == null) return null
            return DataInputStream(input.buffered(INPUT_BUFFER_SIZE))
        }
    }
}

fun loadRecordedTrace(inp: InputStream): Pair<TraceContext, List<TRTracePoint>> {
    DataInputStream(inp.buffered(INPUT_BUFFER_SIZE)).use { input ->
        val magic = input.readLong()
        if (magic != TRACE_MAGIC) {
            error("Wrong magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}")
        }

        val version = input.readLong()
        if (version != TRACE_VERSION) {
            error("Wrong version $version (expected $TRACE_VERSION)")
        }

        val context = TRACE_CONTEXT // TraceContext()
        val roots = mutableListOf<TRTracePoint>()
        val stringCache = mutableListOf<String?>()
        // Load objects
        val eof = loadObjectsEagerly(input, context, stringCache, roots)
        check(eof == ObjectKind.EOF) {
            "Input contains unbalanced method call tracepoint: footer without main data"
        }

        return context to roots
    }
}

typealias TracePointProcessor = (DataInput, TraceContext, MutableList<String?>) -> Unit

private fun loadObjectsEagerly(input: DataInput, context: TraceContext, stringCache: MutableList<String?>, tracepoints: MutableList<TRTracePoint>): ObjectKind =
    loadObjects(input, context, stringCache, true) { input, context, stringCache ->
        tracepoints.add(loadTracePointEagerly(input, context,  stringCache))
    }

private fun loadObjects(input: DataInput, context: TraceContext, stringCache: MutableList<String?>, restore: Boolean, tpProcessor: TracePointProcessor): ObjectKind {
    while (true) {
        val kind = input.readKind()
        when (kind) {
            ObjectKind.CLASS_DESCRIPTOR -> loadClassDescriptor(input, context, restore)
            ObjectKind.METHOD_DESCRIPTOR -> loadMethodDescriptor(input, context, restore)
            ObjectKind.FIELD_DESCRIPTOR -> loadFieldDescriptor(input, context, restore)
            ObjectKind.VARIABLE_DESCRIPTOR -> loadVariableDescriptor(input, context, restore)
            ObjectKind.STRING -> loadString(input, stringCache, restore)
            ObjectKind.CODE_LOCATION -> loadCodeLocation(input, context, stringCache, restore)
            ObjectKind.TRACEPOINT -> tpProcessor(input, context, stringCache)
            ObjectKind.TRACEPOINT_FOOTER -> return kind
            ObjectKind.EOF -> return kind
        }
    }
}

private fun loadTracePointEagerly(input: DataInput, context: TraceContext, stringCache: MutableList<String?>): TRTracePoint {
    val tracePoint = loadTRTracePoint(input)
    if (tracePoint !is TRMethodCallTracePoint) return tracePoint
    // Load all children
    val kind = loadObjectsEagerly(input, context, stringCache, tracePoint.events)
    check(kind == ObjectKind.TRACEPOINT_FOOTER) {
        "Input contains unbalanced method call tracepoint: tracepoint without footer"
    }
    tracePoint.loadFooter(input)
    return tracePoint
}

private fun loadClassDescriptor(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
) : Int {
    val id = input.readInt()
    val descriptor = input.readClassDescriptor()
    if (restore)
        context.restoreClassDescriptor(id,descriptor)
    return id
}

private fun loadMethodDescriptor(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
) : Int {
    val id = input.readInt()
    val descriptor = input.readMethodDescriptor(context)
    if (restore)
        context.restoreMethodDescriptor(id,descriptor)
    return id
}

private fun loadFieldDescriptor(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
) : Int {
    val id = input.readInt()
    val descriptor = input.readFieldDescriptor(context)
    if (restore)
        context.restoreFieldDescriptor(id,descriptor)
    return id
}

private fun loadVariableDescriptor(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
) : Int {
    val id = input.readInt()
    val descriptor = input.readVariableDescriptor()
    if (restore)
        context.restoreVariableDescriptor(id,descriptor)
    return id
}

private fun loadString(
    input: DataInput,
    stringCache: MutableList<String?>,
    restore: Boolean
) : Int {
    val id = input.readInt()
    val value = input.readUTF()
    if (restore) {
        while (stringCache.size <= id) {
            stringCache.add(null)
        }
        stringCache[id] = value
    }
    return id
}

private fun loadCodeLocation(
    input: DataInput,
    context: TraceContext,
    stringCache: MutableList<String?>,
    restore: Boolean
) : Int {
    val id = input.readInt()

    val fileNameId = input.readInt()
    val classNameId = input.readInt()
    val methodNameId = input.readInt()
    val lineNumber = input.readInt()

    if (restore) {
        val ste = StackTraceElement(
            /* declaringClass = */ stringCache[classNameId],
            /* methodName = */ stringCache[methodNameId],
            /* fileName = */ stringCache[fileNameId],
            /* lineNumber = */ lineNumber
        )
        context.restoreCodeLocation(id, ste)
    }
    return id
}

private class SeekableInputStream(
    private val channel: SeekableByteChannel
): InputStream() {
    private var mark: Long = 0
    private val buffer = ByteBuffer.allocate(65536)

    override fun read(): Int {
        buffer.clear()
        buffer.limit(1)
        if (channel.read(buffer) != 1) return -1
        buffer.rewind()
        return buffer.get().toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        var pos = off
        while (pos < off + len) {
            buffer.clear()
            val toRead = min(buffer.capacity(), len - (pos - off))
            buffer.limit(toRead)
            val r = channel.read(buffer)
            if (r <= 0) return pos - off
            buffer.rewind()
            buffer.get(b, pos, r)
            pos += r
        }
        return pos - off
    }

    override fun skip(n: Long): Long {
        val oldPos = channel.position()
        channel.position(max(0L, oldPos + n))
        return channel.position() - oldPos
    }

    override fun available(): Int {
        return (channel.size() - channel.position()).toInt()
    }

    override fun close() {
        channel.close()
    }

    override fun mark(readlimit: Int) {
        mark = channel.position()
    }

    override fun reset() {
        channel.position(mark)
    }

    fun seek(pos: Long) {
        channel.position(pos)
    }

    fun position(): Long {
        return channel.position()
    }

    override fun markSupported(): Boolean = true
}

private class SeekableDataInput (
    private val stream: SeekableInputStream
) : DataInputStream(stream) {
    fun seek(pos: Long) {
        stream.seek(pos)
    }

    fun position(): Long {
        return stream.position()
    }
}
