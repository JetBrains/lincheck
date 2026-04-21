/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.collections.SimpleBitmap
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

private class InMemoryTraceContextSavedState: SimpleTraceContextSavedState() {
    override val seenClassDescriptors = SimpleBitmap(1024)
    override val seenMethodDescriptors = SimpleBitmap(1024)
    override val seenFieldDescriptors = SimpleBitmap(1024)
    override val seenVariableDescriptors = SimpleBitmap(1024)
    override val seenStringDescriptors = SimpleBitmap(1024)
    override val seenCodeLocationDescriptors = SimpleBitmap(65536)
    override val seenAccessPathDescriptors = SimpleBitmap(1024)
}

internal class DirectTraceWriter(
    dataStream: OutputStream,
    indexStream: OutputStream,
    context: TraceContext,
    override val contextState: TraceContextSavedState = InMemoryTraceContextSavedState(),
    private val pos: PositionCalculatingOutputStream = PositionCalculatingOutputStream(dataStream),
) : ContextAwareTraceWriter(
    context = context,
    dataStream = pos,
    dataOutput = DataOutputStream(pos)
) {
    private val index = DataOutputStream(indexStream)

    private var currentWriterId: Int = 0
    private var currentBlockStart: Long = 0 // with header
    private var currentDataStart: Long = 0 // after header

    private var indexBytes: Long = 0

    override val currentDataPosition: Long get() = pos.currentPosition - currentDataStart
    override val writerId: Int get() = currentWriterId

    init {
        dataOutput.writeLong(TRACE_MAGIC)
        dataOutput.writeLong(TRACE_VERSION)

        index.writeLong(INDEX_MAGIC)
        index.writeLong(TRACE_VERSION)
        indexBytes += Long.SIZE_BYTES * 2
    }

    override fun close() {
        super.close()
        index.writeLong(INDEX_MAGIC)
        indexBytes += Long.SIZE_BYTES
        index.close()

        Logger.info { "Data size: ${pos.currentPosition} bytes" }
        Logger.info { "Index size: $indexBytes bytes" }
    }

    override fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
        if (kind == ObjectKind.TRACEPOINT) {
            index.writeIndexCell(kind, id, startPos, endPos)
        } else {
            index.writeIndexCell(kind, id, startPos + currentDataStart, endPos + currentDataStart)
        }
        indexBytes += INDEX_CELL_SIZE
    }

    fun startNewRoot(id: Int) {
        currentWriterId = id
        currentBlockStart = pos.currentPosition
        dataOutput.writeKind(ObjectKind.BLOCK_START)
        dataOutput.writeInt(id)
        currentDataStart = pos.currentPosition
    }

    fun endRoot() {
        val endPos = pos.currentPosition
        dataOutput.writeKind(ObjectKind.BLOCK_END)
        index.writeIndexCell(ObjectKind.BLOCK_START, currentWriterId, currentBlockStart, endPos)
        indexBytes += INDEX_CELL_SIZE
    }
}

class MemoryTraceCollecting(private val context: TraceContext): TraceCollectingStrategy {
    val points = AtomicLong(0)

    override fun registerCurrentThread(threadId: Int) {
        context.setThreadName(threadId, Thread.currentThread().name)
    }

    override fun completeThread(thread: Thread) {}

    override fun tracePointCreated(
        parent: TRContainerTracePoint?,
        created: TRTracePoint
    ) {
        points.incrementAndGet()
        parent?.addChild(created)
    }

    override fun completeContainerTracePoint(thread: Thread, container: TRContainerTracePoint) {}

    /**
     * Do nothing.
     * Trace collected in memory can be saved by external means, if needed.
     */
    override fun traceEnded() {
        Logger.info { "Collected ${points.get()} points" }
    }
}

/**
 * Top-level function to save full-depth recorded trace old-style (all in once)
 */
fun saveRecorderTrace(baseFileName: String, context: TraceContext, rootCallsPerThread: List<TRTracePoint>) {
    val (data, index) = openNewStandardDataAndIndex(baseFileName)
    return saveRecorderTrace(
        data = data,
        index = index,
        context = context,
        rootCallsPerThread = rootCallsPerThread
    )
}

fun saveRecorderTrace(data: OutputStream, index: OutputStream, context: TraceContext, rootCallsPerThread: List<TRTracePoint>) {
    DirectTraceWriter(data, index, context).use { tw ->
        rootCallsPerThread.forEachIndexed { id, root ->
            tw.startNewRoot(id)
            tw.writeThreadName(id, context.getThreadName(id))
            saveTRTracepoint(tw, root)
            tw.endRoot()
        }
    }
}

private fun saveTRTracepoint(writer: TraceWriter, tracepoint: TRTracePoint) {
    tracepoint.save(writer)
    if (tracepoint is TRContainerTracePoint) {
        tracepoint.events.forEach {
            if (it != null) {
                saveTRTracepoint(writer, it)
            }
        }
        tracepoint.saveFooter(writer)
    }
}

private fun DataOutput.writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long) {
    writeByte(kind.ordinal)
    writeInt(id)
    writeLong(startPos)
    writeLong(endPos)
}
