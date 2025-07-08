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

import org.jetbrains.kotlinx.lincheck.tracedata.*
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue

// Buffer for saving trace in one piece
private const val OUTPUT_BUFFER_SIZE: Int = 16 * 1024 * 1024

// 1 MiB
private const val PER_THREAD_DATA_BUFFER_SIZE: Int = 1024 * 1024

// When flush per-thread data block?
private const val DATA_FLUSH_THRESHOLD: Int = 1024

/**
 * It is a strategy to collect trace: it can be full-track in memory or streaming to a file on-the-fly
 */
interface TraceCollectingStrategy {
    /**
     * Register current thread in strategy.
     */
    fun registerCurrentThread(threadId: Int)

    /**
     * Mark the thread as finished in strategy.
     */
    fun finishCurrentThread()

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
 * It is a strategy to save one tracepoint.
 */
internal interface TraceWriter : DataOutput, Closeable {
    /**
     * Saves dependencies of [TRObject], if needed.
     * This must be called before [startWriteAnyTracepoint] for all used [TRObject]s.
     */
    fun preWriteTRObject(value: TRObject?)

    /**
     * Saves [TRObject] itself.
     * Must be called after [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter].
     */
    fun writeTRObject(value: TRObject?)

    /**
     * Marks the beginning of a tracepoint (before the first byte of tracepoint is written).
     */
    fun startWriteAnyTracepoint()

    /**
     * Marks the end of the leaf (fix-sized) tracepoint.
     */
    fun endWriteLeafTracepoint()

    /**
     * Mark the end of the container tracepoint's header (now only TRMethodCallTracepoint is a container one).
     */
    fun endWriteContainerTracepointHeader(id: Int)

    /**
     * Mark the beginning of container tracepoint's footer (After all children are saved).
     */
    fun startWriteContainerTracepointFooter(id: Int)

    /**
     * Marks the end of container tracepoint's footer.
     */
    fun endWriteContainerTracepointFooter()

    /**
     * Write [ClassDescriptor] from context referred by given `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all used class descriptors.
     */
    fun writeClassDescriptor(id: Int)

    /**
     * Write [MethodDescriptor] from context referred by given `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all used method descriptors.
     */
    fun writeMethodDescriptor(id: Int)

    /**
     * Write [FieldDescriptor] from context referred by given `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all used field descriptors.
     */
    fun writeFieldDescriptor(id: Int)

    /**
     * Write [VariableDescriptor] from context referred by given `id` if needed.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all used variable descriptors.
     */
    fun writeVariableDescriptor(id: Int)

    /**
     * Write [StackTraceElement] from context referred by given code location `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all used code locations.
     */
    fun writeCodeLocation(id: Int)
}

/**
 * Interface to check and mark if a given piece of reference data was already stored.
 */
private interface ContextSavingState {
    fun isClassDescriptorSaved(id: Int): Boolean
    fun markClassDescriptorSaved(id: Int)
    fun isMethodDescriptorSaved(id: Int): Boolean
    fun markMethodDescriptorSaved(id: Int)
    fun isFieldDescriptorSaved(id: Int): Boolean
    fun markFieldDescriptorSaved(id: Int)
    fun isVariableDescriptorSaved(id: Int): Boolean
    fun markVariableDescriptorSaved(id: Int)
    fun isCodeLocationSaved(id: Int): Boolean
    fun markCodeLocationSaved(id: Int)

    /**
     * Return positive string id if it was stored already and negative string id if it should be stored
     * with absolute value of this id.
     */
    fun isStringSaved(value: String): Int

    /**
     * Mark string as stored. Do nothing if string was not passed tp [isStringSaved].
     */
    fun markStringSaved(value: String)
}

private sealed class TraceWriterBase(
    private val context: TraceContext,
    private val contextState: ContextSavingState,
    protected val data: DataOutputStream
): TraceWriter, DataOutput by data {
    // Stack of "container" tracepoints
    private val containerStack = mutableListOf<Pair<Int, Long>>()

    private var inTracepointBody = false

    protected abstract val currentDataPosition: Long
    protected abstract val writerId: Int

    override fun close() {
        data.writeKind(ObjectKind.EOF)
        data.close()

        writeIndexCell(ObjectKind.EOF,-1, -1, -1)
    }

    override fun preWriteTRObject(value: TRObject?) {
        check(!inTracepointBody) { "Cannot write TRObject dependency into tracepoint body" }
        if (value == null || value.isPrimitive || value.isSpecial) return
        writeClassDescriptor(value.classNameId)
    }

    override fun writeTRObject(value: TRObject?) {
        check(inTracepointBody) { "Cannot write TRObject outside tracepoint body" }
        data.writeTRObject(value)
    }

    override fun startWriteAnyTracepoint() {
        check(!inTracepointBody) { "Cannot start nested tracepoint body" }
        data.writeKind(ObjectKind.TRACEPOINT)
        inTracepointBody = true
    }

    override fun endWriteLeafTracepoint() {
        check(inTracepointBody) { "Cannot end tracepoint body not in tracepoint" }
        inTracepointBody = false
    }

    override fun endWriteContainerTracepointHeader(id: Int) {
        check(inTracepointBody) { "Cannot end tracepoint header not in tracepoint" }
        inTracepointBody = false

        // Store where container content starts
        containerStack.add(id to currentDataPosition)
    }

    override fun startWriteContainerTracepointFooter(id: Int) {
        check(!inTracepointBody) { "Cannot start nested tracepoint footer" }
        inTracepointBody = true

        check(containerStack.isNotEmpty()) {
            "Calls endWriteContainerTracepointHeader(?) / startWriteContainerTracepointFooter($id) are not balanced"
        }
        val (storedId, startPos) = containerStack.removeLast()
        check(id == storedId) {
            "Calls endWriteContainerTracepointHeader($storedId) / startWriteContainerTracepointFooter($id) are not balanced"
        }
        writeIndexCell(ObjectKind.TRACEPOINT, id, startPos, currentDataPosition)

        // Start object
        data.writeKind(ObjectKind.TRACEPOINT_FOOTER)
    }

    override fun endWriteContainerTracepointFooter() {
        check(inTracepointBody) { "Cannot end tracepoint footer not in tracepoint" }
        inTracepointBody = false
    }

    override fun writeClassDescriptor(id: Int) {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (contextState.isClassDescriptorSaved(id)) return
        // Write class descriptor into data and position into index
        val position = currentDataPosition
        data.writeKind(ObjectKind.CLASS_DESCRIPTOR)
        data.writeInt(id)
        data.writeClassDescriptor(context.getClassDescriptor(id))
        contextState.markClassDescriptorSaved(id)

        writeIndexCell(ObjectKind.CLASS_DESCRIPTOR, id, position, -1)
    }

    override fun writeMethodDescriptor(id: Int) {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (contextState.isMethodDescriptorSaved(id)) return
        val descriptor = context.getMethodDescriptor(id)
        writeClassDescriptor(descriptor.classId)

        // Write method descriptor into data and position into index
        val position = currentDataPosition
        data.writeKind(ObjectKind.METHOD_DESCRIPTOR)
        data.writeInt(id)
        data.writeMethodDescriptor(descriptor)
        contextState.markMethodDescriptorSaved(id)

        writeIndexCell(ObjectKind.METHOD_DESCRIPTOR, id, position, -1)
    }

    override fun writeFieldDescriptor(id: Int) {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (contextState.isFieldDescriptorSaved(id)) return
        val descriptor = context.getFieldDescriptor(id)
        writeClassDescriptor(descriptor.classId)
        // Write field descriptor into data and position into index
        val position = currentDataPosition
        data.writeKind(ObjectKind.FIELD_DESCRIPTOR)
        data.writeInt(id)
        data.writeFieldDescriptor(descriptor)
        contextState.markFieldDescriptorSaved(id)

        writeIndexCell(ObjectKind.FIELD_DESCRIPTOR, id, position, -1)
    }

    override fun writeVariableDescriptor(id: Int) {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (contextState.isVariableDescriptorSaved(id)) return
        // Write variable descriptor into data and position into index
        val position = currentDataPosition
        data.writeKind(ObjectKind.VARIABLE_DESCRIPTOR)
        data.writeInt(id)
        data.writeVariableDescriptor(context.getVariableDescriptor(id))
        contextState.markVariableDescriptorSaved(id)

        writeIndexCell(ObjectKind.VARIABLE_DESCRIPTOR, id, position, -1)
    }

    override fun writeCodeLocation(id: Int) {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (id == UNKNOWN_CODE_LOCATION_ID) return
        if (contextState.isCodeLocationSaved(id)) return

        val codeLocation = context.stackTrace(id)
        // All strings only once. It will have duplications with class and method descriptors,
        // but size loss is negligible and this way is simplier
        val fileNameId = writeString(codeLocation.fileName)
        val classNameId = writeString(codeLocation.className)
        val methodNameId = writeString(codeLocation.methodName)

        // Code location into data and position into index
        val position = currentDataPosition
        data.writeKind(ObjectKind.CODE_LOCATION)
        data.writeInt(id)
        data.writeInt(fileNameId)
        data.writeInt(classNameId)
        data.writeInt(methodNameId)
        data.writeInt(codeLocation.lineNumber)
        contextState.markCodeLocationSaved(id)

        writeIndexCell(ObjectKind.CODE_LOCATION, id, position, -1)
    }

    protected fun writeString(value: String?): Int {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (value == null) return -1

        val id = contextState.isStringSaved(value)
        if (id > 0) return id

        val position = currentDataPosition
        data.writeKind(ObjectKind.STRING)
        data.writeInt(-id)
        data.writeUTF(value)
        contextState.markStringSaved(value)

        // It cannot fail
        writeIndexCell(ObjectKind.STRING, -id, position, -1)

        return -id
    }

    protected abstract fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long)
}

internal data class IndexCell(
    val kind: ObjectKind,
    val id: Int,
    val startPos: Long,
    val endPos: Long
)

private interface BlockSaver {
    fun saveDataAndIndexBlock(writerId: Int, logicalBlockStart: Long, dataBlock: ByteBuffer, indexList: List<IndexCell>)
}

private class BufferedTraceWriter (
    override val writerId: Int,
    context: TraceContext,
    contextState: ContextSavingState,
    private val storage: BlockSaver,
    private val dataStream: ByteBufferOutputStream = ByteBufferOutputStream(PER_THREAD_DATA_BUFFER_SIZE)
) : TraceWriterBase(
    context = context,
    contextState = contextState,
    data = DataOutputStream(dataStream)
) {
    private var currentStartDataPosition: Long = 0
    private val index = mutableListOf<IndexCell>()

    override val currentDataPosition: Long get() = currentStartDataPosition + dataStream.position()

    override fun close() {
        flush()
        super.close()
    }

    override fun endWriteLeafTracepoint() {
        super.endWriteLeafTracepoint()
        maybeFlushData()
    }

    override fun endWriteContainerTracepointFooter() {
        super.endWriteContainerTracepointFooter()
        maybeFlushData()
    }

    override fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
        index.add(IndexCell(kind, id, startPos, endPos))
    }

    fun flush() {
        val logicalStart = currentStartDataPosition
        currentStartDataPosition += dataStream.position()
        storage.saveDataAndIndexBlock(writerId, logicalStart, dataStream.getBuffer(), index)
        dataStream.reset()
        index.clear()
    }

    private fun maybeFlushData() {
        if (dataStream.available() < DATA_FLUSH_THRESHOLD) {
            flush()
        }
    }
}

private class SimpleContextSavingState: ContextSavingState {
    private var seenClassDescriptors = BooleanArray(1024)
    private var seenMethodDescriptors = BooleanArray(1024)
    private var seenFieldDescriptors = BooleanArray(1024)
    private var seenVariableDescriptors = BooleanArray(1024)
    private var seenCodeLocations = BooleanArray(65536)
    private val stringCache = mutableMapOf<String, Int>()

    override fun isClassDescriptorSaved(id: Int): Boolean {
        return id < seenClassDescriptors.size && seenClassDescriptors[id]
    }

    override fun markClassDescriptorSaved(id: Int) {
        seenClassDescriptors = ensureSize(seenClassDescriptors, id)
        seenClassDescriptors[id] = true
    }

    override fun isMethodDescriptorSaved(id: Int): Boolean {
        return id < seenMethodDescriptors.size && seenMethodDescriptors[id]
    }

    override fun markMethodDescriptorSaved(id: Int) {
        seenMethodDescriptors = ensureSize(seenMethodDescriptors, id)
        seenMethodDescriptors[id] = true
    }

    override fun isFieldDescriptorSaved(id: Int): Boolean {
        return id < seenFieldDescriptors.size && seenFieldDescriptors[id]
    }

    override fun markFieldDescriptorSaved(id: Int) {
        seenFieldDescriptors = ensureSize(seenFieldDescriptors, id)
        seenFieldDescriptors[id] = true
    }

    override fun isVariableDescriptorSaved(id: Int): Boolean {
        return id < seenVariableDescriptors.size && seenVariableDescriptors[id]
    }

    override fun markVariableDescriptorSaved(id: Int) {
        seenVariableDescriptors = ensureSize(seenVariableDescriptors, id)
        seenVariableDescriptors[id] = true
    }

    override fun isCodeLocationSaved(id: Int): Boolean {
        return id < seenCodeLocations.size && seenCodeLocations[id]
    }

    override fun markCodeLocationSaved(id: Int) {
        seenCodeLocations = ensureSize(seenCodeLocations, id)
        seenCodeLocations[id] = true
    }

    override fun isStringSaved(value: String): Int {
        val id = stringCache[value]
        if (id != null && id > 0) {
            return id
        }
        stringCache[value] = -(stringCache.size + 1)
        return -stringCache.size
    }

    override fun markStringSaved(value: String) {
        val id = stringCache[value]
        if (id != null && id < 0) {
            stringCache[value] = -id
        }
    }

    private fun ensureSize(map: BooleanArray, id: Int): BooleanArray {
        if (id < map.size) return map
        val newlen = Integer.max(id + 16, map.size + map.size / 2)
        return map.copyOf(newlen)
    }
}

private class DirectTraceWriter (
    dataStream: OutputStream,
    indexStream: OutputStream,
    context: TraceContext,
    private val pos: PositionCalculatingOutputStream = PositionCalculatingOutputStream(dataStream)
) : TraceWriterBase(
    context = context,
    contextState = SimpleContextSavingState(),
    data = DataOutputStream(pos)
) {
    private val index = DataOutputStream(indexStream)

    private var currentWriterId: Int = 0
    private var currentBlockStart: Long = 0 // with header
    private var currentDataStart: Long = 0 // after header

    override val currentDataPosition: Long get() = pos.currentPosition - currentDataStart
    override val writerId: Int get() = currentWriterId

    init {
        data.writeLong(TRACE_MAGIC)
        data.writeLong(TRACE_VERSION)

        index.writeLong(INDEX_MAGIC)
        index.writeLong(TRACE_VERSION)
    }

    override fun close() {
        super.close()
        index.writeLong(INDEX_MAGIC)
        index.close()
    }

    override fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
        if (kind == ObjectKind.TRACEPOINT) {
            index.writeIndexCell(kind, id, startPos, endPos)
        } else {
            index.writeIndexCell(kind, id, startPos + currentDataStart, endPos + currentDataStart)
        }
    }

    fun startNewRoot(id: Int) {
        currentWriterId = id
        currentBlockStart = pos.currentPosition
        data.writeKind(ObjectKind.BLOCK_START)
        data.writeInt(id)
        currentDataStart = pos.currentPosition
    }

    fun endRoot() {
        val endPos = pos.currentPosition
        data.writeKind(ObjectKind.BLOCK_END)
        index.writeIndexCell(ObjectKind.BLOCK_START, currentWriterId, currentBlockStart, endPos)
    }
}

class MemoryTraceCollecting: TraceCollectingStrategy {
    override fun registerCurrentThread(threadId: Int) {}
    override fun finishCurrentThread() {}

    override fun tracePointCreated(
        parent: TRMethodCallTracePoint?,
        created: TRTracePoint
    ) {
        parent?.addChild(created)
    }

    override fun callEnded(callTracepoint: TRMethodCallTracePoint) {}

    /**
     * Do nothing.
     * Trace collected in memory can be saved by external means, if needed.
     */
    override fun traceEnded() {}
}

class FileStreamingTraceCollecting(
    dataStream: OutputStream,
    indexStream: OutputStream,
    val context: TraceContext
): TraceCollectingStrategy, ContextSavingState {
    constructor(baseFileName: String, context: TraceContext) :
            this(
                dataStream = openNewFile(baseFileName),
                indexStream = openNewFile(baseFileName + INDEX_FILENAME_SUFFIX),
                context = context
            )

    private val pos: PositionCalculatingOutputStream = PositionCalculatingOutputStream(dataStream)
    private val data: DataOutputStream = DataOutputStream(pos)
    private val index: DataOutputStream = DataOutputStream(indexStream.buffered(OUTPUT_BUFFER_SIZE))

    private var seenClassDescriptors = AtomicBitmap()
    private var seenMethodDescriptors = AtomicBitmap()
    private var seenFieldDescriptors = AtomicBitmap()
    private var seenVariableDescriptors = AtomicBitmap()
    private var seenCodeLocations = AtomicBitmap()
    private val stringCache = ConcurrentHashMap<String, Int>()
    private val stringIdGenerator = AtomicInteger(1)

    private val writers = ConcurrentHashMap<Thread, BufferedTraceWriter>()

    init {
        data.writeLong(TRACE_MAGIC)
        data.writeLong(TRACE_VERSION)

        index.writeLong(INDEX_MAGIC)
        index.writeLong(TRACE_VERSION)
    }

    override fun registerCurrentThread(threadId: Int) {
        val t = Thread.currentThread()
        if (writers[t] != null) return
        writers[t] = BufferedTraceWriter(
            writerId = threadId,
            context = context,
            contextState = this,
            // This is needed to work around visibility problems
            storage = object : BlockSaver {
                override fun saveDataAndIndexBlock(writerId: Int, logicalBlockStart: Long, dataBlock: ByteBuffer, indexList: List<IndexCell>) =
                    saveDataAndIndexBlockImpl(writerId, logicalBlockStart,dataBlock, indexList)
            }
        )
    }

    override fun finishCurrentThread() {
        val writer = writers[Thread.currentThread()] ?: return
        writer.flush()
    }

    override fun tracePointCreated(
        parent: TRMethodCallTracePoint?,
        created: TRTracePoint
    ) {
        val writer = writers[Thread.currentThread()] ?: return
        try {
            created.save(writer)
        } catch (_: BufferOverflowException) {
            // Flush current buffers, start over
            writer.flush()
            created.save(writer)
        }
    }

    override fun callEnded(callTracepoint: TRMethodCallTracePoint) {
        val writer = writers[Thread.currentThread()] ?: return
        try {
            callTracepoint.saveFooter(writer)
        } catch (_: BufferOverflowException) {
            // Flush current buffers, start over
            writer.flush()
            callTracepoint.saveFooter(writer)
        }
    }

    override fun traceEnded() {
        writers.values.forEach { it.close() }

        data.writeKind(ObjectKind.EOF)
        data.close()

        index.writeIndexCell(ObjectKind.EOF, -1, -1, -1)
        index.writeLong(INDEX_MAGIC)
        index.close()
    }

    // This is a synchronization point for all real stream writes
    @Synchronized
    private fun saveDataAndIndexBlockImpl(writerId: Int, logicalBlockStart: Long, dataBlock: ByteBuffer, indexList: List<IndexCell>) {
        val startPosition = pos.currentPosition
        data.writeKind(ObjectKind.BLOCK_START)
        data.writeInt(writerId)
        val indexShift = pos.currentPosition - logicalBlockStart
        data.write(dataBlock.array(), 0, dataBlock.limit())
        val endPosition = pos.currentPosition
        data.writeKind(ObjectKind.BLOCK_END)
        data.flush()

        index.writeIndexCell(ObjectKind.BLOCK_START, writerId, startPosition, endPosition)
        indexList.forEach {
            if (it.kind == ObjectKind.TRACEPOINT) {
                // Trace points indices are in "local" offsets, as they should be loaded
                // from interleaving per-thread blocks
                index.writeIndexCell(it.kind, it.id, it.startPos, it.endPos)
            } else {
                // All other objects are in "global" offsets as they cannot be split between blocks
                // and can be loaded without taking blocks in consideration
                index.writeIndexCell(it.kind, it.id, it.startPos + indexShift, it.endPos + indexShift)
            }
        }
        index.flush()
    }

    override fun isClassDescriptorSaved(id: Int): Boolean = seenClassDescriptors.isSet(id)

    override fun markClassDescriptorSaved(id: Int): Unit = seenClassDescriptors.set(id)

    override fun isMethodDescriptorSaved(id: Int): Boolean = seenMethodDescriptors.isSet(id)

    override fun markMethodDescriptorSaved(id: Int): Unit = seenMethodDescriptors.set(id)

    override fun isFieldDescriptorSaved(id: Int): Boolean = seenFieldDescriptors.isSet(id)

    override fun markFieldDescriptorSaved(id: Int): Unit = seenFieldDescriptors.set(id)

    override fun isVariableDescriptorSaved(id: Int): Boolean = seenVariableDescriptors.isSet(id)

    override fun markVariableDescriptorSaved(id: Int): Unit = seenVariableDescriptors.set(id)

    override fun isCodeLocationSaved(id: Int): Boolean = seenCodeLocations.isSet(id)

    override fun markCodeLocationSaved(id: Int): Unit = seenCodeLocations.set(id)

    override fun isStringSaved(value: String): Int {
        val id = stringCache.computeIfAbsent(value) { _ -> -stringIdGenerator.getAndIncrement() }
        return id
    }

    override fun markStringSaved(value: String) {
        // Make positive!
        stringCache.compute(value) { _, v -> v?.absoluteValue }
    }
}

/**
 * Top-level function to save full-depth recorded trace old-style (all in once)
 */
fun saveRecorderTrace(baseFileName: String, context: TraceContext, rootCallsPerThread: List<TRTracePoint>) =
    saveRecorderTrace(
        data = openNewFile(baseFileName).buffered(OUTPUT_BUFFER_SIZE),
        index = openNewFile(baseFileName + INDEX_FILENAME_SUFFIX).buffered(OUTPUT_BUFFER_SIZE),
        context = context,
        rootCallsPerThread = rootCallsPerThread
    )

fun saveRecorderTrace(data: OutputStream, index: OutputStream, context: TraceContext, rootCallsPerThread: List<TRTracePoint>) {
    check(context == TRACE_CONTEXT) { "Now only global TRACE_CONTEXT is supported" }

    DirectTraceWriter(data, index, context).use { tw ->
        rootCallsPerThread.forEachIndexed { id, root ->
            tw.startNewRoot(id)
            saveTRTracepoint(tw, root)
            tw.endRoot()
        }
    }
}

private fun saveTRTracepoint(writer: TraceWriter, tracepoint: TRTracePoint) {
    tracepoint.save(writer)
    if (tracepoint is TRMethodCallTracePoint) {
        tracepoint.events.forEach {
            if (it != null) {
                saveTRTracepoint(writer, it)
            }
        }
        tracepoint.saveFooter(writer)
    }
}

private fun DataOutputStream.writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
    writeByte(kind.ordinal)
    writeInt(id)
    writeLong(startPos)
    writeLong(endPos)
}
