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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.absoluteValue
import kotlin.math.max

// 1 MiB
private const val PER_THREAD_DATA_BUFFER_SIZE: Int = 1024 * 1024

// When flush per-thread data block?
private const val DATA_FLUSH_THRESHOLD: Int = 1024

/**
 * It is a strategy to collect trace: it can be full-track in memory or streaming to a file on-the-fly
 */
internal interface TraceCollectingStrategy {
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

private class BufferOverflowException: Exception()

private class ByteBufferOutputStream(
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

    fun position(): Int = buffer.position()
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

        writeIndexCell(ObjectKind.CLASS_DESCRIPTOR, id, position)
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

        writeIndexCell(ObjectKind.METHOD_DESCRIPTOR, id, position)
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

        writeIndexCell(ObjectKind.FIELD_DESCRIPTOR, id, position)
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

        writeIndexCell(ObjectKind.VARIABLE_DESCRIPTOR, id, position)
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

        writeIndexCell(ObjectKind.CODE_LOCATION, id, position)
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
        writeIndexCell(ObjectKind.STRING, -id, position)

        return id
    }

    protected abstract fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long = -1)
}

internal data class IndexCell(
    val kind: ObjectKind,
    val id: Int,
    val startPos: Long,
    val endPos: Long
)

private interface BlockSaver {
    fun saveDataAndIndexBlock(writerId: Int, dataBlock: ByteBuffer, indexList: List<IndexCell>)
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
        storage.saveDataAndIndexBlock(writerId, dataStream.getBuffer(), index)
        index.clear()
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
        currentStartDataPosition += dataStream.position()
        storage.saveDataAndIndexBlock(writerId, dataStream.getBuffer(), index)
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

private class PositionCalculatingOutputStream(
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
    private var currentBlockStart: Long = 0

    override val currentDataPosition: Long get() = pos.currentPosition
    override val writerId: Int get() = currentWriterId

    override fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) = index.writeIndexCell(kind, id, startPos, endPos)

    fun startNewRoot(id: Int) {
        currentWriterId = id
        currentBlockStart = currentDataPosition
        data.writeKind(ObjectKind.BLOCK_START)
        data.writeInt(id)
    }

    fun endRoot() {
        val endPos = currentDataPosition
        data.writeKind(ObjectKind.BLOCK_END)
        writeIndexCell(ObjectKind.BLOCK_START, currentWriterId, currentBlockStart, endPos)
    }
}

internal class MemoryTraceCollecting: TraceCollectingStrategy {
    override fun registerCurrentThread(threadId: Int) = Unit
    override fun finishCurrentThread() = Unit

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

private const val ATOMIC_SIZE_BITS = Long.SIZE_BITS
private const val ATOMIC_SIZE_SHIFT = 6
private const val ATOMIC_BIT_MASK = 0x3F

private class AtomicBitmap(size: Int) {
    private val bitmap = AtomicReference(AtomicLongArray(size / ATOMIC_SIZE_BITS))

    fun isSet(id: Int): Boolean {
        val idx = id shr ATOMIC_SIZE_SHIFT
        val bit = (1 shl (id and ATOMIC_BIT_MASK)).toLong()
        return isSet(idx, bit)
    }

    fun set(id: Int) {
        val idx = id shr ATOMIC_SIZE_SHIFT
        val bit = (1 shl (id and ATOMIC_BIT_MASK)).toLong()

        val b = bitmap.get()
        if (idx > b.length()) {
            resizeBitmap(idx)
        }
        while (!isSet(idx, bit)) {
            do {
                val b = bitmap.get()
                val oldVal = b.get(idx)
            } while (!b.compareAndSet(idx, oldVal, oldVal or bit))
        }
    }

    private fun isSet(idx: Int, bit: Long): Boolean {
        val b = bitmap.get()
        if (idx >= b.length()) return false
        val value = b.get(idx)
        return (value and bit) != 0L
    }

    private fun resizeBitmap(idx: Int) {
        do {
            val b = bitmap.get()
            // Another thread was faster
            if (b.length() > idx) return

            val newLen = max(idx + 1, b.length() + b.length() / 2)
            val newB = AtomicLongArray(newLen)
            for (i in 0..< b.length()) {
                newB.lazySet(i,b.get(i))
            }
        } while (!bitmap.compareAndSet(b, newB))
    }
}

internal class FileStreamingTraceCollecting(
    dataStream: OutputStream,
    indexStream: OutputStream,
    val context: TraceContext
): TraceCollectingStrategy, BlockSaver, ContextSavingState {
    constructor(baseFileName: String, context: TraceContext) :
            this(
                dataStream = openNewFile(baseFileName),
                indexStream = openNewFile(baseFileName + INDEX_FILENAME_SUFFIX),
                context = context
            )

    private val pos: PositionCalculatingOutputStream = PositionCalculatingOutputStream(dataStream)
    private val data: DataOutputStream = DataOutputStream(pos)
    private val index: DataOutputStream = DataOutputStream(indexStream)

    private var seenClassDescriptors = AtomicBitmap(context.classDescriptors.size * 2)
    private var seenMethodDescriptors = AtomicBitmap(context.methodDescriptors.size * 2)
    private var seenFieldDescriptors = AtomicBitmap(context.fieldDescriptors.size * 2)
    private var seenVariableDescriptors = AtomicBitmap(context.variableDescriptors.size * 2)
    private var seenCodeLocations = AtomicBitmap(context.codeLocations.size * 2)
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
        writers[t] = BufferedTraceWriter(threadId, context, this, this)
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
    }

    // This is a synchronization point for all real stream writes
    @Synchronized
    override fun saveDataAndIndexBlock(writerId: Int, dataBlock: ByteBuffer, indexList: List<IndexCell>) {
        val startPosition = pos.currentPosition
        data.writeKind(ObjectKind.BLOCK_START)
        data.writeInt(writerId)
        val indexShift = pos.currentPosition
        data.write(dataBlock.array(), 0, dataBlock.limit())
        val endPosition = pos.currentPosition
        data.writeKind(ObjectKind.BLOCK_END)

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
    }

    override fun isClassDescriptorSaved(id: Int): Boolean = seenClassDescriptors.isSet(id)

    override fun markClassDescriptorSaved(id: Int) = seenClassDescriptors.set(id)

    override fun isMethodDescriptorSaved(id: Int): Boolean = seenMethodDescriptors.isSet(id)

    override fun markMethodDescriptorSaved(id: Int) = seenMethodDescriptors.set(id)

    override fun isFieldDescriptorSaved(id: Int): Boolean = seenFieldDescriptors.isSet(id)

    override fun markFieldDescriptorSaved(id: Int) = seenFieldDescriptors.set(id)

    override fun isVariableDescriptorSaved(id: Int): Boolean = seenVariableDescriptors.isSet(id)

    override fun markVariableDescriptorSaved(id: Int) = seenVariableDescriptors.set(id)

    override fun isCodeLocationSaved(id: Int): Boolean = seenCodeLocations.isSet(id)

    override fun markCodeLocationSaved(id: Int) = seenCodeLocations.set(id)

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
        data = openNewFile(baseFileName),
        index = openNewFile(baseFileName + INDEX_FILENAME_SUFFIX),
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
            saveTRTracepoint(writer, it)
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
