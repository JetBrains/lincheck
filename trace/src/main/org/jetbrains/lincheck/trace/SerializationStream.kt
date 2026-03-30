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

import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.util.Logger
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.reflect.KClass

// 1 MiB
private const val PER_THREAD_DATA_BUFFER_SIZE: Int = 1024 * 1024

// When flush per-thread data block?
private const val DATA_FLUSH_THRESHOLD: Int = 1024

internal data class IndexCell(
    val kind: ObjectKind,
    val id: Int,
    val startPos: Long,
    val endPos: Long
)

/**
 * Snapshot of the trace context portion contained in a specific data block.
 * Tracks which descriptors, code locations, and other context elements are written in this block.
 * Used to defer marking these elements as "saved" until the block is persisted to disk.
 */
internal data class BlockContextSnapshot(
    val classDescriptorIds: Set<Int> = emptySet(),
    val methodDescriptorIds: Set<Int> = emptySet(),
    val fieldDescriptorIds: Set<Int> = emptySet(),
    val variableDescriptorIds: Set<Int> = emptySet(),
    val stringIds: Set<Int> = emptySet(),
    val codeLocationIds: Set<Int> = emptySet(),
    val accessPaths: Set<AccessPath> = emptySet()
)

internal interface BlockSaver {
    fun saveDataAndIndexBlock(writerId: Int, logicalBlockStart: Long, dataBlock: ByteBuffer, indexList: List<IndexCell>, contextSnapshot: BlockContextSnapshot)
}


// Leave some space for metadata
private const val MAX_STRING_SIZE = PER_THREAD_DATA_BUFFER_SIZE - 1024

internal class BufferedTraceWriter(
    override val writerId: Int,
    context: TraceContext,
    contextState: TraceContextSavedState,
    private val storage: BlockSaver,
    private val bufferStream: ByteBufferOutputStream = ByteBufferOutputStream(PER_THREAD_DATA_BUFFER_SIZE)
) : ContextAwareTraceWriter(
    context = context,
    contextState = contextState,
    dataStream = bufferStream,
    dataOutput = bufferStream
) {
    private var currentStartDataPosition: Long = 0
    private var index = mutableListOf<IndexCell>()

    // Track what's written in the current block for the snapshot
    private val classDescriptorIdsInBlock = mutableSetOf<Int>()
    private val methodDescriptorIdsInBlock = mutableSetOf<Int>()
    private val fieldDescriptorIdsInBlock = mutableSetOf<Int>()
    private val variableDescriptorIdsInBlock = mutableSetOf<Int>()
    private val stringIdsInBlock = mutableSetOf<Int>()
    private val codeLocationIdsInBlock = mutableSetOf<Int>()
    private val accessPathsInBlock = mutableSetOf<AccessPath>()

    override val currentDataPosition: Long get() = currentStartDataPosition + bufferStream.position()

    override fun isDescriptorSavedInContext(descriptorClass: KClass<*>, id: Int): Boolean {
        // Check if already globally saved OR written in the current block
        val inBlock = when (descriptorClass) {
            ClassDescriptor::class -> id in classDescriptorIdsInBlock
            MethodDescriptor::class -> id in methodDescriptorIdsInBlock
            FieldDescriptor::class -> id in fieldDescriptorIdsInBlock
            VariableDescriptor::class -> id in variableDescriptorIdsInBlock
            String::class -> id in stringIdsInBlock
            else -> false
        }
        return inBlock || super.isDescriptorSavedInContext(descriptorClass, id)
    }

    override fun markDescriptorSavedInContext(descriptorClass: KClass<*>, id: Int) {
        // Don't mark globally; accumulate in the block snapshot instead.
        // Actual global marking happens in FileStreamingThread after disk writing
        when (descriptorClass) {
            ClassDescriptor::class -> classDescriptorIdsInBlock.add(id)
            MethodDescriptor::class -> methodDescriptorIdsInBlock.add(id)
            FieldDescriptor::class -> fieldDescriptorIdsInBlock.add(id)
            VariableDescriptor::class -> variableDescriptorIdsInBlock.add(id)
            String::class -> stringIdsInBlock.add(id)
        }
    }

    override fun isCodeLocationSavedInContext(id: Int): Boolean {
        return (id in codeLocationIdsInBlock) || super.isCodeLocationSavedInContext(id)
    }

    override fun markCodeLocationSavedInContext(id: Int) {
        // Marking is deferred until FileStreamingThread persists block to disk
        codeLocationIdsInBlock.add(id)
    }

    override fun isAccessPathSavedInContext(value: AccessPath): Int {
        if (value in accessPathsInBlock) {
            // Already in the current block, return positive (saved)
            return super.isAccessPathSavedInContext(value).absoluteValue
        }
        return super.isAccessPathSavedInContext(value)
    }

    override fun markAccessPathSavedInContext(value: AccessPath) {
        // Marking is deferred until FileStreamingThread persists block to disk
        accessPathsInBlock.add(value)
    }

    override fun close() {
        flush()
        super.close()
    }

    override fun endWriteLeafTracepoint() {
        super.endWriteLeafTracepoint()
        maybeFlushData()
    }

    override fun endWriteContainerTracepointFooter(id: Int) {
        super.endWriteContainerTracepointFooter(id)
        maybeFlushData()
    }

    override fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
        index.add(IndexCell(kind, id, startPos, endPos))
        // Rollback to here in case of overflow, as the index cell is written after all
        // object data, it means that the object is in data buffer completely.
        bufferStream.mark()
    }

    // Cut string to half a buffer size
    override fun writeString(value: String?): Int {
        val trimmedValue = if ((value?.length ?: 0) > MAX_STRING_SIZE) value?.substring(0, MAX_STRING_SIZE) else value
        return super.writeString(trimmedValue)
    }

    fun mark() {
        bufferStream.mark()
    }

    fun rollback() {
        resetTracepointState()
        bufferStream.rollback()
    }

    fun flush() {
        val logicalStart = currentStartDataPosition
        currentStartDataPosition += bufferStream.position()
        // Don't allocate new index if it is empty
        val indexToSave = if (index.isEmpty()) {
            emptyList() // Singleton
        } else {
            val oldIndex = index
            index = mutableListOf()
            oldIndex
        }

        // Create snapshot of what's being saved in this block
        val snapshot = BlockContextSnapshot(
            classDescriptorIds = classDescriptorIdsInBlock.toSet(),
            methodDescriptorIds = methodDescriptorIdsInBlock.toSet(),
            fieldDescriptorIds = fieldDescriptorIdsInBlock.toSet(),
            variableDescriptorIds = variableDescriptorIdsInBlock.toSet(),
            stringIds = stringIdsInBlock.toSet(),
            codeLocationIds = codeLocationIdsInBlock.toSet(),
            accessPaths = accessPathsInBlock.toSet()
        )

        // Clear for next block
        classDescriptorIdsInBlock.clear()
        methodDescriptorIdsInBlock.clear()
        fieldDescriptorIdsInBlock.clear()
        variableDescriptorIdsInBlock.clear()
        stringIdsInBlock.clear()
        codeLocationIdsInBlock.clear()
        accessPathsInBlock.clear()

        storage.saveDataAndIndexBlock(writerId, logicalStart, bufferStream.detachBuffer(), indexToSave, snapshot)
    }

    private fun maybeFlushData() {
        if (bufferStream.available() < DATA_FLUSH_THRESHOLD) {
            flush()
        }
    }
}

private class FileStreamingThread(
    dataStream: OutputStream,
    indexStream: OutputStream,
    private val savedState: TraceContextSavedState
): Thread() {
    private sealed class Job()
    private data class SaveBlockJob(val writerId: Int, val logicalBlockStart: Long, val dataBlock: ByteBuffer, val indexList: List<IndexCell>, val contextSnapshot: BlockContextSnapshot): Job()
    private class ExitJob(): Job()

    private val pos: PositionCalculatingOutputStream = PositionCalculatingOutputStream(dataStream)
    private val data: OutputStream = pos
    private val index: OutputStream = indexStream.buffered(OUTPUT_BUFFER_SIZE)

    private var bufferSize = 1024
    private var buffer: ByteBuffer = ByteBuffer.allocate(INDEX_CELL_SIZE * bufferSize)

    /**
     * We should finish current `put` operation even if the thread is interrupted.
     */
    private class TraceSerializationTaskQueue(capacity: Int) : ArrayBlockingQueue<Job>(capacity) {
        override fun put(job: Job) {
            var wasInterrupted = false
            while (true) {
                try {
                    super.put(job)
                    if (wasInterrupted) {
                        // Restore the interrupt status that was consumed by put() loop above.
                        currentThread().interrupt()
                    }
                    return
                } catch (_: InterruptedException) {
                    // Remember the interrupt and retry until we manage to enqueue the job.
                    wasInterrupted = true
                }
            }
        }
    }

    private val queue = TraceSerializationTaskQueue(1024)

    val dataBytes: Long = pos.currentPosition

    var indexBytes: Long = 0
        private set

    init {
        name = "TR-Block-Writer"

        data.writeLong(TRACE_MAGIC)
        data.writeLong(TRACE_VERSION)

        index.writeLong(INDEX_MAGIC)
        index.writeLong(TRACE_VERSION)
        indexBytes += Long.SIZE_BYTES * 2
    }

    fun addBlock(writerId: Int, logicalBlockStart: Long, dataBlock: ByteBuffer, indexList: List<IndexCell>, contextSnapshot: BlockContextSnapshot) {
        queue.put(SaveBlockJob(writerId, logicalBlockStart, dataBlock, indexList, contextSnapshot))
    }

    fun exit() {
        queue.put(ExitJob())
        join()
    }

    override fun run() {
        while (true) {
            when (val j = queue.take()) {
                is SaveBlockJob -> saveBlock(j)
                is ExitJob -> {
                    closeStreams()
                    break
                }
            }
        }
    }

    private fun saveBlock(job: SaveBlockJob) {
        val startPosition = pos.currentPosition
        data.writeKind(ObjectKind.BLOCK_START)
        data.writeInt(job.writerId)
        val indexShift = pos.currentPosition - job.logicalBlockStart
        data.write(job.dataBlock.array(), 0, job.dataBlock.limit())
        val endPosition = pos.currentPosition
        data.writeKind(ObjectKind.BLOCK_END)
        data.flush()

        if (bufferSize < job.indexList.size + 1) {
            bufferSize = max(job.indexList.size + 1, (bufferSize * 1.5).toInt())
            buffer = ByteBuffer.allocate(INDEX_CELL_SIZE * bufferSize)
        } else {
            buffer.clear()
        }

        buffer.putIndexCell(ObjectKind.BLOCK_START, job.writerId, startPosition, endPosition)
        job.indexList.forEach {
            if (it.kind == ObjectKind.TRACEPOINT) {
                // Trace points indices are in "local" offsets, as they should be loaded
                // from interleaving per-thread blocks
                buffer.putIndexCell(it.kind, it.id, it.startPos, it.endPos)
            } else {
                // All other objects are in "global" offsets as they cannot be split between blocks
                // and can be loaded without taking blocks in consideration
                buffer.putIndexCell(it.kind, it.id, it.startPos + indexShift, it.endPos + indexShift)
            }
        }
        buffer.flip()
        indexBytes += buffer.limit()
        index.write(buffer.array(), 0, buffer.limit())
        index.flush()

        // TODO: this could be done before writing to the disk, because the order in which this data will be written is already fixed, should I do it?
        // Mark all items in this block as saved after successful disk write
        job.contextSnapshot.apply {
            classDescriptorIds.forEach { savedState.markDescriptorSaved<ClassDescriptor>(it) }
            methodDescriptorIds.forEach { savedState.markDescriptorSaved<MethodDescriptor>(it) }
            fieldDescriptorIds.forEach { savedState.markDescriptorSaved<FieldDescriptor>(it) }
            variableDescriptorIds.forEach { savedState.markDescriptorSaved<VariableDescriptor>(it) }
            stringIds.forEach { savedState.markDescriptorSaved<String>(it) }
            codeLocationIds.forEach { savedState.markCodeLocationSaved(it) }
            accessPaths.forEach { savedState.markAccessPathSaved(it) }
        }
    }

    private fun closeStreams() {
        data.writeKind(ObjectKind.EOF)
        data.close()

        buffer.clear()
        buffer.putIndexCell(ObjectKind.EOF, -1, -1, -1)
        buffer.flip()
        indexBytes += buffer.limit()
        index.write(buffer.array(), 0, buffer.limit())
        index.writeLong(INDEX_MAGIC)
        indexBytes += Long.SIZE_BYTES
        index.close()
    }

    private companion object {
        private val staticBuffer = ByteBuffer.allocate(Long.SIZE_BYTES)

        private fun OutputStream.writeKind(v: ObjectKind) {
            write(v.ordinal)
        }

        private fun OutputStream.writeInt(v: Int) {
            staticBuffer.clear()
            staticBuffer.putInt(v)
            staticBuffer.flip()
            write(staticBuffer.array(), 0, Int.SIZE_BYTES)
        }

        private fun OutputStream.writeLong(v: Long) {
            staticBuffer.clear()
            staticBuffer.putLong(v)
            staticBuffer.flip()
            write(staticBuffer.array(), 0, Long.SIZE_BYTES)
        }

        private fun ByteBuffer.putKind(v: ObjectKind) {
            put(v.ordinal.toByte())
        }

        private fun ByteBuffer.putIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
            putKind(kind)
            putInt(id)
            putLong(startPos)
            putLong(endPos)
        }
    }
}

class FileStreamingTraceCollecting(
    dataStream: OutputStream,
    indexStream: OutputStream,
    val context: TraceContext
): TraceCollectingStrategy, TraceContextSavedState {
    constructor(baseFileName: String, context: TraceContext) :
            this(
                dataStream = openNewFile(baseFileName),
                indexStream = openNewFile("$baseFileName.$INDEX_FILENAME_EXT"),
                context = context
            )

    private val ioThread = FileStreamingThread(dataStream, indexStream, savedState = this)
    init {
        ioThread.start()
    }

    private val points = AtomicInteger(0)

    private var seenStringDescriptors = AtomicBitmap()
    private var seenClassDescriptors = AtomicBitmap()
    private var seenMethodDescriptors = AtomicBitmap()
    private var seenFieldDescriptors = AtomicBitmap()
    private var seenVariableDescriptors = AtomicBitmap()
    private var seenCodeLocations = AtomicBitmap()
    private val accessPathEnumerator = Enumerator<AccessPath>()

    private val writers = ConcurrentHashMap<Thread, BufferedTraceWriter>()

    private class Enumerator<T : Any> {
        private val idGenerator = AtomicInteger(1)
        private val cache = ConcurrentHashMap<T, Int>()

        fun isSaved(value: T): Int {
            val id = cache.computeIfAbsent(value) { _ -> -idGenerator.getAndIncrement() }
            return id
        }

        fun makeSaved(value: T) {
            // Make positive!
            cache.compute(value) { _, v -> v?.absoluteValue }
        }
    }

    override fun registerCurrentThread(threadId: Int) {
        val thread = Thread.currentThread()
        if (writers[thread] != null) return
        writers[thread] = BufferedTraceWriter(
            writerId = threadId,
            context = context,
            contextState = this,
            // This is needed to work around visibility problems
            storage = object : BlockSaver {
                override fun saveDataAndIndexBlock(
                    writerId: Int,
                    logicalBlockStart: Long,
                    dataBlock: ByteBuffer,
                    indexList: List<IndexCell>,
                    contextSnapshot: BlockContextSnapshot
                ) = ioThread.addBlock(writerId, logicalBlockStart, dataBlock, indexList, contextSnapshot)
            }
        )
        context.setThreadName(threadId, thread.name)
    }

    override fun completeThread(thread: Thread) {
        val writer = writers[thread] ?: return
        writer.flush()
    }

    override fun tracePointCreated(
        parent: TRContainerTracePoint?,
        created: TRTracePoint
    ) {
        points.incrementAndGet()

        val writer = writers[Thread.currentThread()] ?: return
        try {
            writer.mark()
            created.save(writer)
        } catch (_: BufferOverflowException) {
            // Flush current buffers, start over
            writer.rollback()
            writer.flush()
            created.save(writer)
        }
    }

    override fun completeContainerTracePoint(thread: Thread, container: TRContainerTracePoint) {
        val writer = writers[thread] ?: return
        try {
            writer.mark()
            container.saveFooter(writer)
        } catch (_: BufferOverflowException) {
            // Flush current buffers, start over
            writer.rollback()
            writer.flush()
            container.saveFooter(writer)
        }
    }

    override fun traceEnded() {
        writers.entries.forEach { (t, w) ->
            // writerId matches the assigned id of the thread
            w.writeThreadName(w.writerId, t.name)
        }
        writers.values.forEach { it.close() }

        // Flush all output & exit
        ioThread.exit()

        Logger.info { "Collected ${points.get()} points" }
        Logger.info { "Data size: ${ioThread.dataBytes} bytes" }
        Logger.info { "Index size: ${ioThread.indexBytes} bytes" }
    }

    override fun isDescriptorSaved(descriptorClass: KClass<*>, id: Int): Boolean {
        val bitmap = getDescriptorBitmap(descriptorClass) ?: return false
        return bitmap.isSet(id)
    }

    override fun markDescriptorSaved(descriptorClass: KClass<*>, id: Int) {
        val bitmap = getDescriptorBitmap(descriptorClass) ?: return
        bitmap.set(id)
    }

    private fun getDescriptorBitmap(descriptorClass: KClass<*>): AtomicBitmap? {
        return when (descriptorClass) {
            ClassDescriptor::class -> seenClassDescriptors
            MethodDescriptor::class -> seenMethodDescriptors
            FieldDescriptor::class -> seenFieldDescriptors
            VariableDescriptor::class -> seenVariableDescriptors
            String::class -> seenStringDescriptors
            else -> {
                Logger.error { "Unknown descriptor class: ${descriptorClass::class}" }
                null
            }
        }
    }

    override fun isCodeLocationSaved(id: Int): Boolean = seenCodeLocations.isSet(id)

    override fun markCodeLocationSaved(id: Int): Unit = seenCodeLocations.set(id)

    override fun isAccessPathSaved(value: AccessPath): Int = accessPathEnumerator.isSaved(value)

    override fun markAccessPathSaved(value: AccessPath): Unit = accessPathEnumerator.makeSaved(value)
}
