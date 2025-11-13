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
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import kotlin.math.max

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

private interface BlockSaver {
    fun saveDataAndIndexBlock(writerId: Int, logicalBlockStart: Long, dataBlock: ByteBuffer, indexList: List<IndexCell>)
}


// Leave some space for metadata
private const val MAX_STRING_SIZE = PER_THREAD_DATA_BUFFER_SIZE - 1024

private class BufferedTraceWriter (
    override val writerId: Int,
    context: TraceContext,
    contextState: ContextSavingState,
    private val storage: BlockSaver,
    private val bufferStream: ByteBufferOutputStream = ByteBufferOutputStream(PER_THREAD_DATA_BUFFER_SIZE)
) : TraceWriterBase(
    context = context,
    contextState = contextState,
    dataStream = bufferStream,
    dataOutput = bufferStream
) {
    private var currentStartDataPosition: Long = 0
    private var index = mutableListOf<IndexCell>()

    override val currentDataPosition: Long get() = currentStartDataPosition + bufferStream.position()

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
        storage.saveDataAndIndexBlock(writerId, logicalStart, bufferStream.detachBuffer(), indexToSave)
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
): Thread() {
    private sealed class Job()
    private data class SaveBlockJob(val writerId: Int, val logicalBlockStart: Long, val dataBlock: ByteBuffer, val indexList: List<IndexCell>): Job()
    private class ExitJob(): Job()

    private val pos: PositionCalculatingOutputStream = PositionCalculatingOutputStream(dataStream)
    private val data: OutputStream = pos
    private val index: OutputStream = indexStream.buffered(OUTPUT_BUFFER_SIZE)

    private var bufferSize = 1024
    private var buffer: ByteBuffer = ByteBuffer.allocate(INDEX_CELL_SIZE * bufferSize)

    private val queue = object : ArrayBlockingQueue<Job>(1024) {
        override fun put(job: Job) {
            var isInterrupted = false
            while (true) {
                try {
                    super.put(job)
                    if (isInterrupted) {
                        currentThread().interrupt()
                    }
                    return
                } catch (_: InterruptedException) {
                    isInterrupted = true
                }
            }
        }
    }

    init {
        name = "TR-Block-Writer"

        data.writeLong(TRACE_MAGIC)
        data.writeLong(TRACE_VERSION)

        index.writeLong(INDEX_MAGIC)
        index.writeLong(TRACE_VERSION)
    }

    fun addBlock(writerId: Int, logicalBlockStart: Long, dataBlock: ByteBuffer, indexList: List<IndexCell>) {
        queue.put(SaveBlockJob(writerId, logicalBlockStart, dataBlock, indexList))
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
        index.write(buffer.array(), 0, buffer.limit())
        index.flush()
    }

    private fun closeStreams() {
        data.writeKind(ObjectKind.EOF)
        data.close()

        buffer.clear()
        buffer.putIndexCell(ObjectKind.EOF, -1, -1, -1)
        buffer.flip()
        index.write(buffer.array(), 0, buffer.limit())
        index.writeLong(INDEX_MAGIC)
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
): TraceCollectingStrategy, ContextSavingState {
    constructor(baseFileName: String, context: TraceContext) :
            this(
                dataStream = openNewFile(baseFileName),
                indexStream = openNewFile("$baseFileName.$INDEX_FILENAME_EXT"),
                context = context
            )

    private val ioThread = FileStreamingThread(dataStream, indexStream)
    init {
        ioThread.start()
    }

    private var seenClassDescriptors = AtomicBitmap()
    private var seenMethodDescriptors = AtomicBitmap()
    private var seenFieldDescriptors = AtomicBitmap()
    private var seenVariableDescriptors = AtomicBitmap()
    private var seenCodeLocations = AtomicBitmap()
    private val stringEnumerator = Enumerator<String>()
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
                override fun saveDataAndIndexBlock(writerId: Int, logicalBlockStart: Long, dataBlock: ByteBuffer, indexList: List<IndexCell>) =
                    ioThread.addBlock(writerId, logicalBlockStart, dataBlock, indexList)
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

    override fun isStringSaved(value: String): Int = stringEnumerator.isSaved(value)

    override fun markStringSaved(value: String): Unit = stringEnumerator.makeSaved(value)

    override fun isAccessPathSaved(value: AccessPath): Int = accessPathEnumerator.isSaved(value)

    override fun markAccessPathSaved(value: AccessPath): Unit = accessPathEnumerator.makeSaved(value)
}
