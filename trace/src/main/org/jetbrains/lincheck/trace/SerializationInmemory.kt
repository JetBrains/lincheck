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
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.OutputStream

private class SimpleContextSavingState: ContextSavingState {
    private var seenClassDescriptors = BooleanArray(1024)
    private var seenMethodDescriptors = BooleanArray(1024)
    private var seenFieldDescriptors = BooleanArray(1024)
    private var seenVariableDescriptors = BooleanArray(1024)
    private var seenCodeLocations = BooleanArray(65536)
    private val stringCache = Enumerator<String>()
    private val accessPathCache = Enumerator<AccessPath>()

    private class Enumerator<T : Any> {
        private val cache = mutableMapOf<T, Int>()

        fun isSaved(value: T): Int {
            val id = cache[value]
            if (id != null && id > 0) {
                return id
            }
            cache[value] = -(cache.size + 1)
            return -cache.size
        }

        fun makeSaved(value: T) {
            val id = cache[value]
            if (id != null && id < 0) {
                cache[value] = -id
            }
        }
    }

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
        return stringCache.isSaved(value)
    }

    override fun markStringSaved(value: String) {
        stringCache.makeSaved(value)
    }

    override fun isAccessPathSaved(value: AccessPath): Int {
        return accessPathCache.isSaved(value)
    }

    override fun markAccessPathSaved(value: AccessPath) {
        accessPathCache.makeSaved(value)
    }

    private fun ensureSize(map: BooleanArray, id: Int): BooleanArray {
        if (id < map.size) return map
        val newlen = Integer.max(id + 16, map.size + map.size / 2)
        return map.copyOf(newlen)
    }
}

private class DirectTraceWriter(
    dataStream: OutputStream,
    indexStream: OutputStream,
    context: TraceContext,
    private val pos: PositionCalculatingOutputStream = PositionCalculatingOutputStream(dataStream),
) : TraceWriterBase(
    context = context,
    contextState = SimpleContextSavingState(),
    dataStream = pos,
    dataOutput = DataOutputStream(pos)
) {
    private val index = DataOutputStream(indexStream)

    private var currentWriterId: Int = 0
    private var currentBlockStart: Long = 0 // with header
    private var currentDataStart: Long = 0 // after header

    override val currentDataPosition: Long get() = pos.currentPosition - currentDataStart
    override val writerId: Int get() = currentWriterId

    init {
        dataOutput.writeLong(TRACE_MAGIC)
        dataOutput.writeLong(TRACE_VERSION)

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
        dataOutput.writeKind(ObjectKind.BLOCK_START)
        dataOutput.writeInt(id)
        currentDataStart = pos.currentPosition
    }

    fun endRoot() {
        val endPos = pos.currentPosition
        dataOutput.writeKind(ObjectKind.BLOCK_END)
        index.writeIndexCell(ObjectKind.BLOCK_START, currentWriterId, currentBlockStart, endPos)
    }
}

class MemoryTraceCollecting(private val context: TraceContext): TraceCollectingStrategy {
    override fun registerCurrentThread(threadId: Int) {
        context.setThreadName(threadId, Thread.currentThread().name)
    }

    override fun completeThread(thread: Thread) {}

    override fun tracePointCreated(
        parent: TRContainerTracePoint?,
        created: TRTracePoint
    ) {
        parent?.addChild(created)
    }

    override fun completeContainerTracePoint(thread: Thread, container: TRContainerTracePoint) {}

    /**
     * Do nothing.
     * Trace collected in memory can be saved by external means, if needed.
     */
    override fun traceEnded() {}
}

/**
 * Top-level function to save full-depth recorded trace old-style (all in once)
 */
fun saveRecorderTrace(baseFileName: String, context: TraceContext, rootCallsPerThread: List<TRTracePoint>) =
    saveRecorderTrace(
        data = openNewFile(baseFileName).buffered(OUTPUT_BUFFER_SIZE),
        index = openNewFile("$baseFileName.$INDEX_FILENAME_EXT").buffered(OUTPUT_BUFFER_SIZE),
        context = context,
        rootCallsPerThread = rootCallsPerThread
    )

fun saveRecorderTrace(data: OutputStream, index: OutputStream, context: TraceContext, rootCallsPerThread: List<TRTracePoint>) {
    check(context == TRACE_CONTEXT) { "Now only global TRACE_CONTEXT is supported" }

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
