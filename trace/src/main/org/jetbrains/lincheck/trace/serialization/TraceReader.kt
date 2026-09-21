/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.trace.*
import org.jetbrains.lincheck.util.Logger
import java.io.DataInput
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.use

private typealias TraceTree = MutableList<TraceContainerHeaderTracePoint>
/**
 * Tracepoint reader returns "true" if a read is complete and "false" if it encountered the end of the block.
 */
private typealias TracePointReader = (DataInput, TraceContext) -> Boolean

internal interface TracepointConsumer {
    /**
     * Called for every tracepoint record right after its kind byte is read, before its body.
     *
     * This is the only place where a consumer which tracks positions can capture a record's start:
     * the reader has no position of its own to report, and by the time the record is read it is already consumed.
     */
    fun tracePointStarted() {}

    /**
     * Called for every tracepoint record, once it is read.
     *
     * [parent] is the record's container in the trace tree, `null` for a root:
     * for a [TraceContainerFooterTracePoint] that is the container it closes,
     * which is attached to it by the time this is called.
     */
    fun tracePointRead(parent: TraceContainerHeaderTracePoint?, tracePoint: TracePoint)
}

internal interface BlockConsumer {
    fun blockStarted(threadId: Int) {}
    fun blockEnded(threadId: Int) {}
}

private fun readMagic(input: InputStream): Long {
    val buf = ByteBuffer.allocate(8)
    if (input.read(buf.array()) != 8) return 0 // 0 is not magic for sure
    return buf.getLong(0)
}

/**
 * Used by plugin to open files
 */
fun isTraceData(traceFileName: String): Boolean {
    return try {
        val input = openExistingFile(traceFileName) ?: return false
        input.use { input ->
            return readMagic(input) == TRACE_MAGIC
        }
    } catch (_: Throwable) {
        false
    }
}

/**
 * Used by plugin to open files
 */
fun isTraceData(firstBytes: ByteBuffer): Boolean =
    firstBytes.capacity() >= 8 && firstBytes.getLong(0) == TRACE_MAGIC

internal fun loadAllObjectsDeep(
    input: DataInputStream,
    context: TraceContext,
    tracepointConsumer: TracepointConsumer,
    blockConsumer: BlockConsumer
) {
    val trees = mutableMapOf<Int, TraceTree>()
    var seenEOF = false

    while (input.available() > 0) {
        // Start a new block
        val kind = input.readKind()

        // All blocks are read
        if (kind == ObjectKind.EOF) {
            seenEOF = true
            break
        }

        check(kind == ObjectKind.BLOCK_START) {
            "Unexpected object kind $kind, expected BLOCK_START, broken file"
        }

        val threadId = input.readInt()
        blockConsumer.blockStarted(threadId)

        // A thread's tree is built across all of its blocks: a container may span several of them.
        val tree = trees.computeIfAbsent(threadId) { mutableListOf() }

        // Read objects and tracepoints from this block till it ends
        val blockEndKind = loadObjects(input, context, restore = true) { input, context ->
            loadTracePoint(input, context, tree, tracepointConsumer)
        }
        check(blockEndKind == ObjectKind.BLOCK_END) {
            "Unexpected object kind $blockEndKind, expected BLOCK_END, broken file"
        }
        blockConsumer.blockEnded(threadId)
    }

    if (!seenEOF) {
        Logger.warn { "TraceRecorder: no EOF record at the end of the file" }
    }
    // Check that all stacks are empty
    trees.forEach {
        if (!it.value.isEmpty()) {
            Logger.warn { "TraceRecorder: Thread #${it.key} contains unfinished method calls" }
        }
    }
}

/**
 * Reads one tracepoint record and folds it into [tree], the stack of currently open containers.
 *
 * The trace is decodable strictly forward: every container is delimited by its own
 * [TraceContainerFooterTracePoint], so no seeking is required.
 *
 * @return always `true`: the caller's [loadObjects] loop stops on the block-end record itself.
 */
private fun loadTracePoint(
    input: DataInput,
    context: TraceContext,
    tree: TraceTree,
    consumer: TracepointConsumer
): Boolean {
    consumer.tracePointStarted()
    val tracePoint = input.readTracePointData(context)

    if (tracePoint is TraceContainerFooterTracePoint) {
        val container = tree.removeLastOrNull()
            ?: error("Closing tracepoint for container #${tracePoint.containerEventId} has no open container")
        container.attachFooterTracePoint(tracePoint)
        consumer.tracePointRead(container, tracePoint)
        return true
    }

    consumer.tracePointRead(tree.lastOrNull(), tracePoint)
    if (tracePoint is TraceContainerHeaderTracePoint) {
        tree.add(tracePoint)
    }
    return true
}

internal fun loadObjects(
    input: DataInput,
    context: TraceContext,
    restore: Boolean,
    tracePointReader: TracePointReader
): ObjectKind {
    while (true) {
        when (val kind = input.readKind()) {
            ObjectKind.THREAD_NAME -> loadThreadName(input, context, restore)
            ObjectKind.CLASS_DESCRIPTOR -> loadClassDescriptor(input, context, restore)
            ObjectKind.METHOD_DESCRIPTOR -> loadMethodDescriptor(input, context, restore)
            ObjectKind.FIELD_DESCRIPTOR -> loadFieldDescriptor(input, context, restore)
            ObjectKind.VARIABLE_DESCRIPTOR -> loadVariableDescriptor(input, context, restore)
            ObjectKind.STRING -> loadString(input, context, restore)
            ObjectKind.ACCESS_PATH -> loadAccessPath(input, context, restore)
            ObjectKind.CODE_LOCATION -> loadCodeLocation(input, context, restore)
            ObjectKind.TRACEPOINT -> if (!tracePointReader(input, context)) return ObjectKind.BLOCK_END
            ObjectKind.BLOCK_START, // Should not happen, really
            ObjectKind.BLOCK_END, // Block ended
            ObjectKind.EOF // Should not happen, really; BLOCK_END must go first
                -> return kind
        }
    }
}

internal fun loadThreadName(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
): Int {
    val (id, name) = input.readThreadName()
    if (restore) {
        context.setThreadName(id, name)
    }
    return id
}

internal fun loadClassDescriptor(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
): Int {
    val id = input.readInt()
    val descriptor = input.readClassDescriptor(context)
    if (restore) {
        context.classPool.restore(id, descriptor)
    }
    return id
}

internal fun loadMethodDescriptor(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
): Int {
    val id = input.readInt()
    val descriptor = input.readMethodDescriptor(context)
    if (restore)
        context.methodPool.restore(id, descriptor)
    return id
}

internal fun loadFieldDescriptor(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
): Int {
    val id = input.readInt()
    val descriptor = input.readFieldDescriptor(context)
    if (restore) {
        context.fieldPool.restore(id, descriptor)
    }
    return id
}

internal fun loadVariableDescriptor(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
): Int {
    val id = input.readInt()
    val descriptor = input.readVariableDescriptor(context)
    if (restore) {
        context.variablePool.restore(id, descriptor)
    }
    return id
}

internal fun loadString(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
): Int {
    val id = input.readInt()
    val string = input.readString()
    if (restore) {
        context.stringPool.restore(id, string)
    }
    return id
}

internal fun loadAccessPath(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
): Int {
    val id = input.readInt()
    val len = input.readInt()
    val locations = mutableListOf<AccessLocation>()
    repeat(len) {
        locations.add(input.readAccessLocation(context))
    }
    if (restore) {
        context.restoreAccessPath(id, AccessPath(locations))
    }
    return id
}

internal fun loadCodeLocation(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
): Int {
    val kind = input.readCodeLocationKind()
    val id = input.readInt()

    val fileNameId = input.readInt()
    val classNameId = input.readInt()
    val methodNameId = input.readInt()
    val lineNumber = input.readInt()
    val accessPathId = input.readInt()

    val nArgumentNames = input.readInt()
    val argumentNameIds = when {
        nArgumentNames == 0 -> null
        else -> List(nArgumentNames) { input.readInt() }
    }
    val nActiveLocalsNames = input.readInt()
    val activeLocalsNamesIds = when {
        nActiveLocalsNames == 0 -> null
        else -> List(nActiveLocalsNames) { input.readInt() }
    }
    val activeLocalsKinds = when {
        nActiveLocalsNames == 0 -> null
        else -> List(nActiveLocalsNames) { input.readInt() }
    }
    val nLoopIds = input.readInt()
    val loopIds = List(nLoopIds) { input.readInt() }

    if (restore) {
        val stringPool = context.stringPool
        val stackTraceElement = StackTraceElement(
            stringPool.getOrNull(classNameId) ?: FALLBACK_STRING,
            stringPool.getOrNull(methodNameId) ?: FALLBACK_STRING,
            stringPool.getOrNull(fileNameId) ?: FALLBACK_STRING,
            lineNumber
        )
        val accessPath = if (accessPathId != -1) context.getAccessPath(accessPathId) else null
        val argumentNames = argumentNameIds?.map { if (it != -1) context.getAccessPath(it) else null }
        val activeLocals = if (activeLocalsNamesIds == null || activeLocalsKinds == null) null
                           else activeLocalsNamesIds.map { stringPool.getOrNull(it) ?: FALLBACK_STRING }
                                                    .zip(activeLocalsKinds)
                                                    .map { (name, kind) -> ActiveLocal(name, LocalKind.entries[kind]) }
        val codeLocation = when (kind) {
            CodeLocationKind.LINE -> LineCodeLocation(stackTraceElement, activeLocals)
            CodeLocationKind.ACCESS -> AccessCodeLocation(stackTraceElement, accessPath, activeLocals)
            CodeLocationKind.METHOD_CALL -> MethodCallCodeLocation(stackTraceElement, accessPath, argumentNames, activeLocals)
            CodeLocationKind.LOOP -> LoopHeaderCodeLocation(stackTraceElement, loopIds, activeLocals)
        }
        context.codeLocationsPool.restore(id, codeLocation)
    }
    return id
}

/** Validates the data-file prelude and returns the runtime that produced the trace. */
internal fun checkDataHeader(input: DataInput): String = input.checkTraceHeader()

const val FALLBACK_STRING = "<unknown>"
internal const val INPUT_BUFFER_SIZE: Int = 16 * 1024 * 1024