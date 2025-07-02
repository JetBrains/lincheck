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

import org.jetbrains.lincheck.trace.TRMethodCallTracePoint
import org.jetbrains.lincheck.trace.TRTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import java.io.Closeable
import java.io.DataInput
import java.io.DataInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path


private const val INPUT_BUFFER_SIZE: Int = 16*1024*1024

private typealias StringCache = MutableList<String?>
private typealias CallStack = MutableList<TRMethodCallTracePoint>
private typealias TracePointReader = (DataInput, TraceContext, StringCache, CallStack) -> Unit

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

    private val dataStream: SeekableInputStream
    private val data: SeekableDataInput
    private val perThreadData = mutableMapOf<Int, SeekableChunkedInputStream>()
    private val dataBlockRanges = mutableMapOf<Int, MutableList<Pair<Long, Long>>>()
    private val callTracepointPositions = mutableMapOf<Int, Pair<Long, Long>>()

    init {
        val channel = Files.newByteChannel(Path(dataFileName), StandardOpenOption.READ)
        dataStream = SeekableChannelBufferedInputStream(channel)
        data = SeekableDataInput(dataStream)

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
        loadContext()

        val roots = mutableListOf<TRTracePoint>()

        val kind = loadObjects(data, context, mutableListOf(), false) { input, _, _ ->
            val tracePoint = loadTRTracePoint(input)
            check (tracePoint is TRMethodCallTracePoint) {
                "Expecting TRMethodCallTracepoint, found ${tracePoint.javaClass.simpleName}"
            }
            skipChildrenAndLoadFooter(input as SeekableDataInput, tracePoint)
            roots.add(tracePoint)
        }
        check (kind == ObjectKind.EOF) {
            "Input contains additional data: expected EOF get $kind"
        }

        return roots
    }

    fun readChildren(parent: TRMethodCallTracePoint) {
        val positions = callTracepointPositions[parent.eventId]
        check(positions != null) { "TRMethodCallTracePoint ${parent.eventId} is not found in index" }
        parent.events.clear()
        data.seek(positions.first)
        val kind = loadObjects(data, context, mutableListOf(), false) { input, _, _ ->
            val tracePoint = loadTRTracePoint(input)
            if (tracePoint is TRMethodCallTracePoint) {
                skipChildrenAndLoadFooter(input as SeekableDataInput, tracePoint)
            }
            parent.events.add(tracePoint)
        }
        check (kind == ObjectKind.TRACEPOINT_FOOTER) {
            "Input contains broken data: expected Tracepoint Footer for event ${parent.eventId} got $kind"
        }
        val actualFooterPos = data.position() - 1 // 1 is size of object kind
        check (actualFooterPos == positions.second) {
            "Input contains broken data: expected Tracepoint Footer for event ${parent.eventId} at position ${positions.second}, got $actualFooterPos"
        }
    }

    private fun skipChildrenAndLoadFooter(input: SeekableDataInput, tracePoint: TRMethodCallTracePoint) {
        val positions = callTracepointPositions[tracePoint.eventId]
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

    private fun loadContext() {
        if (index == null) {
            System.err.println("TraceRecorder: No index file is given for ${this.dataFileName}: read whole data file to re-create context")
            loadContextWithoutIndex()
        } else if (!loadContextWithIndex()) {
            System.err.println("TraceRecorder: Index file for $dataFileName id corrupted: read whole data file to re-create context")
            context.clear()
            loadContextWithoutIndex()
        }

        // Create all virtual streams for each thread
        dataBlockRanges.forEach { id, blocks ->
            perThreadData[id] = SeekableChunkedInputStream(dataStream, blocks)
        }

        // Seek to start after magic and version
        data.seek((Long.SIZE_BYTES * 2).toLong())
    }

    private fun loadContextWithIndex(): Boolean {
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
                var seenEOF = false
                while (index.available() >= 21) {
                    val kind = index.readKind()
                    val id = index.readInt()
                    val start = index.readLong()
                    val end = index.readLong()

                    if (kind == ObjectKind.EOF) {
                        seenEOF = true
                        break
                    }

                    if (kind == ObjectKind.TRACEPOINT) {
                        callTracepointPositions[id] = start to end
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
                            ObjectKind.BLOCK_START -> {
                                val list = dataBlockRanges.computeIfAbsent(id) { mutableListOf() }
                                // Move start 5 bytes later to skip the header: Kind (one byte) + Int
                                list.add(start + 5 to end - (start + 5))
                                // Read thread id for consistency check
                                data.readInt()
                            }
                            // Kotlin complains without these branches, though they are unreachable
                            ObjectKind.TRACEPOINT,
                            ObjectKind.EOF -> -1
                            // Cannot be in index
                            ObjectKind.BLOCK_END,
                            ObjectKind.TRACEPOINT_FOOTER -> error("Object $objNum has unexpected kind $kind")
                        }
                        check (id == dataId) {
                            "Object $objNum of kind $kind: expected $id but datafile has $dataId"
                        }
                    }
                    objNum++
                }
                if (!seenEOF) {
                    // Allow for truncated indices, only print warning
                    System.err.println("TraceRecorder: Warning: Index for $dataFileName is truncated")
                } else {
                    val magicEnd = index.readLong()
                    if (magicEnd != INDEX_MAGIC) {
                        error("Wrong final index magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}")
                    }
                }
            } catch (t: Throwable) {
                System.err.println("TraceRecorder: Error reading index for $dataFileName: ${t.message}")
                return false
            }
        }
        return true
    }

    private fun loadContextWithoutIndex() {
        val stringCache = mutableListOf<String?>()
        // We don't bother about block boundaries, so simply convert them into block data for later streams
        // Also, double-definition of objects is Ok in multithreaded environment, as one
        // thread can save the same object as another one, we don't roll back such savings
        // in case of conflict which, theoretically, can be detected
        while (true) {
            val kind = loadObjects(data, context, stringCache, true) { input, context, stringCache ->
                val tracePoint = loadTRTracePoint(input)
                if (tracePoint is TRMethodCallTracePoint) {
                    loadAndThrowAwayChildren(input, context, stringCache, tracePoint)
                }
            }
        }
    }

    private fun loadAndThrowAwayChildren(input: DataInput, context: TraceContext, stringCache: StringCache, parent: TRMethodCallTracePoint) {
        val startPos = (input as SeekableDataInput).position()
        val kind = loadObjects(input, context, stringCache, true) { input, context, stringCache ->
            val tracePoint = loadTRTracePoint(input)
            if (tracePoint is TRMethodCallTracePoint) {
                loadAndThrowAwayChildren(input, context, stringCache, tracePoint)
            }
        }
        check(kind == ObjectKind.TRACEPOINT_FOOTER) {
            "Input contains unbalanced method call tracepoint: tracepoint without footer"
        }
        val endPos = input.position() - 1 // 1 is the size of ObjectKind.TRACEPOINT_FOOTER
        callTracepointPositions[parent.eventId] = startPos to endPos
        parent.loadFooter(input)
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
        check(magic == TRACE_MAGIC) {
            "Wrong magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}"
        }

        val version = input.readLong()
        check (version == TRACE_VERSION) { "Wrong version $version (expected $TRACE_VERSION)" }

        val context = TRACE_CONTEXT // TraceContext()
        val stacks = mutableMapOf<Int, MutableList<TRMethodCallTracePoint>>()
        val stringCache = mutableListOf<String?>()

        var seenEOF = false
        while (input.available() > 0) {
            // Start a new block
            val kind = input.readKind()

            // All blocks are read
            if (kind == ObjectKind.EOF) {
                seenEOF = true
                break
            }

            if (kind != ObjectKind.BLOCK_START) {
                System.err.println("TraceRecorder: Unexpected object kind $kind, broken file")
                break
            }

            val threadId = input.readInt()
            val stack = stacks.computeIfAbsent(threadId) { mutableListOf() }
            // Read objects with this stack

            val lastKind = loadObjects()
        }

        // Check that everything is closed

        return context to roots.values.toList()
    }
}

private fun loadObjectsEagerly(input: DataInput, context: TraceContext, stringCache: StringCache, tracepoints: MutableList<TRTracePoint>): ObjectKind =
    loadObjects(input, context, stringCache, true) { input, context, stringCache ->
        tracepoints.add(loadTracePointDeep(input, context,  stringCache))
    }

private fun loadObjects(
    input: DataInput,
    context: TraceContext,
    stringCache: StringCache,
    restore: Boolean,
    stack: CallStack,
    tracePointReader: TracePointReader
) : ObjectKind {
    while (true) {
        when (val kind = input.readKind()) {
            ObjectKind.CLASS_DESCRIPTOR -> loadClassDescriptor(input, context, restore)
            ObjectKind.METHOD_DESCRIPTOR -> loadMethodDescriptor(input, context, restore)
            ObjectKind.FIELD_DESCRIPTOR -> loadFieldDescriptor(input, context, restore)
            ObjectKind.VARIABLE_DESCRIPTOR -> loadVariableDescriptor(input, context, restore)
            ObjectKind.STRING -> loadString(input, stringCache, restore)
            ObjectKind.CODE_LOCATION -> loadCodeLocation(input, context, stringCache, restore)
            ObjectKind.TRACEPOINT -> tracePointReader(input, context, stringCache, stack)
            ObjectKind.TRACEPOINT_FOOTER, // Children read
            ObjectKind.BLOCK_START, // Should not happen, really
            ObjectKind.BLOCK_END, // Block ended
            ObjectKind.EOF // Should not happen, really; BLOCK_END must go first
                -> return kind
        }
    }
}

private fun loadTracePointDeep(input: DataInput, context: TraceContext, stringCache: StringCache, stack: CallStack): TRTracePoint {
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
        context.restoreClassDescriptor(id, descriptor)
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
        context.restoreMethodDescriptor(id, descriptor)
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
        context.restoreFieldDescriptor(id, descriptor)
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
        context.restoreVariableDescriptor(id, descriptor)
    return id
}

private fun loadString(
    input: DataInput,
    stringCache: StringCache,
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
    stringCache: StringCache,
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
