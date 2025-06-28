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

/**
 * This file contains code needed to serialize trace, both after collection
 * or on-the-flight
 */

private const val OUTPUT_BUFFER_SIZE: Int = 16*1024*1024

/**
 * It is a strategy to collect trace: it can be full-track in memory or streaming to file on-the-fly
 */
internal interface TraceCollectingStrategy {
    /**
     * Must be called when a new tracepoint is created.
     *
     * @param parent Current top of the call stack, if exists.
     * @param created New tracepoint
     */
    fun tracePointCreated(parent: TRMethodCallTracePoint?, created: TRTracePoint)

    /**
     * Must be called when the method call is ended and the call stack is popped.
     *
     * @param callTracepoint tracepoint popped from the call stack.
     */
    fun callEnded(callTracepoint: TRMethodCallTracePoint)

    /**
     * Must be called when trace is finished
     */
    fun traceEnded()
}


/**
 * It is a strategy to save tracepoints.
 */
internal interface TraceWriter : DataOutput, Closeable {
    fun preWriteTRObject(value: TRObject?)
    fun writeTRObject(value: TRObject?)

    fun startWriteAnyTracepoint()

    fun endWriteContainerTracepointHeader(id: Int)
    fun startWriteContainerTracepointFooter(id: Int)

    fun writeClassDescriptor(id: Int)
    fun writeMethodDescriptor(id: Int)
    fun writeFieldDescriptor(id: Int)
    fun writeVariableDescriptor(id: Int)
    fun writeCodeLocation(id: Int)
}

private class PositionSavingOutputStream(
    private val out: OutputStream
) : OutputStream() {
    private var position: Long = 0

    val currentPosition: Long get() = position

    override fun write(b: Int) {
        out.write(b)
        position += 1
    }

    override fun write(b: ByteArray?) {
        out.write(b)
        position += b?.size ?: 0
    }

    override fun write(b: ByteArray?, off: Int, len: Int) {
        out.write(b, off, len)
        position += len
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}

private class TwoStreamTraceWriter private constructor(
    private val pos: PositionSavingOutputStream,
    private val index: DataOutputStream,
    private val context: TraceContext,
    private val data: DataOutputStream = DataOutputStream(pos),
): TraceWriter, DataOutput by data {
    constructor(dataStream: OutputStream, indexStream: OutputStream, context: TraceContext) :
            this(
                pos = PositionSavingOutputStream( dataStream.buffered(OUTPUT_BUFFER_SIZE)),
                index = DataOutputStream(indexStream.buffered(OUTPUT_BUFFER_SIZE)),
                context = context
            )

    private var seenClassDescriptors = BooleanArray(context.classDescriptors.size)
    private var seenMethodDescriptors = BooleanArray(context.methodDescriptors.size)
    private var seenFieldDescriptors = BooleanArray(context.fieldDescriptors.size)
    private var seenVariableDescriptors = BooleanArray(context.variableDescriptors.size)
    private var seenCodeLocations = BooleanArray(context.codeLocations.size)

    private val stringCache = mutableMapOf<String, Int>()

    // Stack of "container" tracepoints
    private val containerStack = mutableListOf<Pair<Int, Long>>()

    init {
        data.writeLong(TRACE_MAGIC)
        data.writeLong(TRACE_VERSION)

        index.writeLong(INDEX_MAGIC)
        index.writeLong(TRACE_VERSION)
    }

    override fun close() {
        writeIndexCell(ObjectKind.EOF, -1, -1, -1)
        index.writeLong(INDEX_MAGIC)
        index.close()

        data.writeKind(ObjectKind.EOF)
        data.close()
    }

    override fun preWriteTRObject(value: TRObject?) {
        if (value == null || value.isPrimitive || value.isSpecial) return
        writeClassDescriptor(value.classNameId)
    }

    override fun writeTRObject(value: TRObject?) = data.writeTRObject(value)

    override fun startWriteAnyTracepoint() = data.writeKind(ObjectKind.TRACEPOINT)

    override fun endWriteContainerTracepointHeader(id: Int) {
        // Store where container content starts
        containerStack.add(id to pos.currentPosition)
    }

    override fun startWriteContainerTracepointFooter(id: Int) {
        check(containerStack.isNotEmpty()) {
            "Calls endWriteContainerTracepointHeader(?) / startWriteContainerTracepointFooter($id) are not balanced"
        }
        val (storedId, startPos) = containerStack.removeLast()
        check(id == storedId) {
            "Calls endWriteContainerTracepointHeader($storedId) / startWriteContainerTracepointFooter($id) are not balanced"
        }
        writeIndexCell(ObjectKind.TRACEPOINT, id, startPos, pos.currentPosition)

        // Start object
        data.writeKind(ObjectKind.TRACEPOINT_FOOTER)
    }

    override fun writeClassDescriptor(id: Int) {
        seenClassDescriptors = ensureSize(seenClassDescriptors, id)
        if (seenClassDescriptors[id]) return
        // Write class descriptor into data and position into index
        val position = pos.currentPosition
        data.writeKind(ObjectKind.CLASS_DESCRIPTOR)
        data.writeInt(id)
        data.writeClassDescriptor(context.getClassDescriptor(id))

        writeIndexCell(ObjectKind.CLASS_DESCRIPTOR, id, position)
        seenClassDescriptors[id] = true
    }

    override fun writeMethodDescriptor(id: Int) {
        seenMethodDescriptors = ensureSize(seenMethodDescriptors, id)
        if (seenMethodDescriptors[id]) return
        val descriptor = context.getMethodDescriptor(id)
        writeClassDescriptor(descriptor.classId)

        // Write method descriptor into data and position into index
        val position = pos.currentPosition
        data.writeKind(ObjectKind.METHOD_DESCRIPTOR)
        data.writeInt(id)
        data.writeMethodDescriptor(descriptor)

        writeIndexCell(ObjectKind.METHOD_DESCRIPTOR, id, position)
        seenMethodDescriptors[id] = true
    }

    override fun writeFieldDescriptor(id: Int) {
        seenFieldDescriptors = ensureSize(seenFieldDescriptors, id)
        if (seenFieldDescriptors[id]) return
        val descriptor = context.getFieldDescriptor(id)
        writeClassDescriptor(descriptor.classId)
        // Write field descriptor into data and position into index
        val position = pos.currentPosition
        data.writeKind(ObjectKind.FIELD_DESCRIPTOR)
        data.writeInt(id)
        data.writeFieldDescriptor(descriptor)

        writeIndexCell(ObjectKind.FIELD_DESCRIPTOR, id, position)
        seenFieldDescriptors[id] = true
    }

    override fun writeVariableDescriptor(id: Int) {
        seenVariableDescriptors = ensureSize(seenVariableDescriptors, id)
        if (seenVariableDescriptors[id]) return
        // Write variable descriptor into data and position into index
        val position = pos.currentPosition
        data.writeKind(ObjectKind.VARIABLE_DESCRIPTOR)
        data.writeInt(id)
        data.writeVariableDescriptor(context.getVariableDescriptor(id))

        writeIndexCell(ObjectKind.VARIABLE_DESCRIPTOR, id, position)
        seenVariableDescriptors[id] = true
    }

    override fun writeCodeLocation(id: Int) {
        if (id == UNKNOWN_CODE_LOCATION_ID) return
        seenCodeLocations = ensureSize(seenCodeLocations, id)
        if (seenCodeLocations[id]) return

        val codeLocation = context.stackTrace(id)
        // All strings only once. It will have duplications with class and method descriptors,
        // but size loss is negligible and this way is simplier
        val fileNameId = writeString(codeLocation.fileName)
        val classNameId = writeString(codeLocation.className)
        val methodNameId = writeString(codeLocation.methodName)

        // Code location into data and position into index
        val position = pos.currentPosition
        data.writeKind(ObjectKind.CODE_LOCATION)
        data.writeInt(id)
        data.writeInt(fileNameId)
        data.writeInt(classNameId)
        data.writeInt(methodNameId)
        data.writeInt(codeLocation.lineNumber)

        writeIndexCell(ObjectKind.CODE_LOCATION, id, position)
        seenCodeLocations[id] = true
    }

    private fun writeString(value: String?): Int {
        if (value == null) return -1

        var id = stringCache[value]
        if (id != null) return id

        id = stringCache.size
        stringCache[value] = id

        val position = pos.currentPosition
        data.writeKind(ObjectKind.STRING)
        data.writeInt(id)
        data.writeUTF(value)

        writeIndexCell(ObjectKind.STRING, id, position)

        return id
    }

    private fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long = -1) {
        index.writeByte(kind.ordinal)
        index.writeInt(id)
        index.writeLong(startPos)
        index.writeLong(endPos)
    }

    private fun ensureSize(map: BooleanArray, id: Int): BooleanArray {
        if (id < map.size) return map
        val newlen = Integer.max(id + 16, map.size + map.size / 2)
        return map.copyOf(newlen)
    }
}

internal class MemoryTraceCollecting: TraceCollectingStrategy {
    override fun tracePointCreated(
        parent: TRMethodCallTracePoint?,
        created: TRTracePoint
    ) {
        parent?.events?.add(created)
    }

    override fun callEnded(callTracepoint: TRMethodCallTracePoint) = Unit

    /**
     * Do nothing.
     * Trace collected in memory can be saved by external means, if needed.
     */
    override fun traceEnded() = Unit
}

internal class FileStreamingTraceCollecting(
    dataStream: OutputStream,
    indexStream: OutputStream,
    context: TraceContext
): TraceCollectingStrategy {
    constructor(baseFileName: String, context: TraceContext) :
            this(
                dataStream = openNewFile(baseFileName),
                indexStream = openNewFile(baseFileName + INDEX_FILENAME_SUFFIX),
                context = context
            )

    private val writer = TwoStreamTraceWriter(dataStream, indexStream, context)

    override fun tracePointCreated(
        parent: TRMethodCallTracePoint?,
        created: TRTracePoint
    ) {
        created.save(writer)
    }

    override fun callEnded(callTracepoint: TRMethodCallTracePoint) {
        callTracepoint.saveFooter(writer)
    }

    override fun traceEnded() {
        writer.close()
    }
}

/**
 * Top-level function to save full-depth recorded trace old-style (all in once)
 */
fun saveRecorderTrace(baseFileName: String, context: TraceContext, rootCallsPerThread: List<TRTracePoint>) =
    saveRecorderTrace(
        data = openNewFile(baseFileName),
        index = openNewFile(baseFileName + INDEX_FILENAME_SUFFIX),
        context = context,
        rootCallsPerThread = rootCallsPerThread
    )

fun saveRecorderTrace(data: OutputStream, index: OutputStream, context: TraceContext, rootCallsPerThread: List<TRTracePoint>) {
    check(context == TRACE_CONTEXT) { "Now only global TRACE_CONTEXT is supported" }

    TwoStreamTraceWriter(data, index, context).use { tw ->
        rootCallsPerThread.forEach { root ->
            saveTRTracepoint(tw, root)
        }
    }
}

private fun saveTRTracepoint(writer: TraceWriter, tracepoint: TRTracePoint) {
    tracepoint.save(writer)
    if (tracepoint is TRMethodCallTracePoint) {
        tracepoint.events.forEach {
            saveTRTracepoint(writer, it)
        }
        tracepoint.saveFooter(writer)
    }
}
