/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.util.Logger
import java.io.DataInput
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.use

private typealias TraceTree = MutableList<TRContainerTracePoint>
private typealias TracePointReader = (DataInput, TraceContext, CodeLocationsContext) -> Boolean

internal interface TracepointConsumer {
    fun tracePointRead(parent: TRContainerTracePoint?, tracePoint: TRTracePoint)
    fun footerStarted(tracePoint: TRContainerTracePoint) {}
}

internal interface BlockConsumer {
    fun blockStarted(threadId: Int) {}
    fun blockEnded(threadId: Int) {}
}

/**
 * Class which describes fully loaded trace.
 */
data class TraceWithContext(
    /**
     * Trace context with all descriptors belonging to given trace
     */
    val context: TraceContext,
    /**
     * Trace meta info. Can be `null` if trace is loaded from non-packed (single) data file.
     */
    val metaInfo: TraceMetaInfo?,
    /**
     * List of all root calls for all traced threads.
     */
    val roots: List<TRTracePoint>,
    /**
     *  If it is diff, map diff thread id to right and left thread ids. If no thread on left or right,
     *  id will be -1
     */
    val diffThreadMap: Map<Int, Pair<Int, Int>>?
)

// TODO: introduce `TraceReader` class and move all `loadXXX` methods there

/**
 * Load trace from file. File can contain unpacked binary trace (without index)
 * or packed trace with metainfo, data and index inside.
 *
 * If trace was loaded from packed file, [TraceWithContext.metaInfo] must be filled in,
 * it will be `null` otherwise.
 */
fun loadRecordedTrace(traceFileName: String): TraceWithContext {
    val input = openExistingFile(traceFileName)?.buffered(INPUT_BUFFER_SIZE)
    require(input != null) { "Cannot open trace \"$traceFileName\"" }

    val dataProvider = TraceDataProvider(traceFileName)
    // We need meta, data file and maybe maps
    dataProvider.use { provider ->
        val input = openExistingFile(provider.dataFileName)
        if (input == null) {
            throw IOException("Cannot open trace \"$traceFileName\" (data file is lost)")
        }
        input.use {
            return loadRecordedTrace(it, provider.metaInfo, provider.threadIdMap)
        }
    }
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

/**
 * Load unpacked trace. [inp] must be stream pointing to binary trace format, not
 * packed in any way.

 * [TraceWithContext.metaInfo] will be `null`.
 */
fun loadRecordedTrace(inp: InputStream): TraceWithContext = loadRecordedTrace(inp, null, null)

internal fun loadRecordedTrace(inp: InputStream, meta: TraceMetaInfo?, threadMap: Map<Int, Pair<Int, Int>>?): TraceWithContext {
    DataInputStream(inp.buffered(INPUT_BUFFER_SIZE)).use { input ->
        checkDataHeader(input)

        // Create an isolated fresh context for this load
        val context = TraceContext()
        val roots = mutableMapOf<Int, MutableList<TRTracePoint>>()

        loadAllObjectsDeep(
            input = input,
            context = context,
            tracepointConsumer = object : TracepointConsumer {
                override fun tracePointRead(
                    parent: TRContainerTracePoint?,
                    tracePoint: TRTracePoint
                ) {
                    if (parent == null) {
                        roots.computeIfAbsent(tracePoint.threadId) { mutableListOf() }.add(tracePoint)
                    } else {
                        parent.addChild(tracePoint)
                    }
                }
            },
            blockConsumer = object : BlockConsumer {}
        )

        roots.forEach {
            if (it.value.size > 1) {
                Logger.warn { "TraceRecorder: Thread #${it.key} contains multiple top-level calls" }
            }
        }

        return TraceWithContext(
            context = context,
            metaInfo = meta,
            roots = roots.values.map { it.first() },
            diffThreadMap = if (meta?.isDiff ?: false) threadMap else null
        )
    }
}

internal fun loadAllObjectsDeep(
    input: DataInputStream,
    context: TraceContext,
    tracepointConsumer: TracepointConsumer,
    blockConsumer: BlockConsumer
) {
    val codeLocs = CodeLocationsContext()
    val trees = mutableMapOf<Int, MutableList<TRContainerTracePoint>>()
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

        val tree = trees.computeIfAbsent(threadId) { mutableListOf() }

        // Read objects and tracepoints from this block till it ends.
        // Unwind the stack manually, if needed
        while (true) {
            val kind = loadObjects(input, context, codeLocs, true) { input, context, codeLocs ->
                loadTracePointDeep(input, context, codeLocs, tree, tracepointConsumer)
            }
            if (kind == ObjectKind.BLOCK_END) {
                blockConsumer.blockEnded(threadId)
                break
            }
            check(kind == ObjectKind.TRACEPOINT_FOOTER) {
                "Unexpected object kind $kind, expected TRACEPOINT_FOOTER, broken file"
            }
            check(tree.isNotEmpty()) { "Stack underflow" }

            val tracePoint = tree.removeLast()
            tracepointConsumer.footerStarted(tracePoint)
            tracePoint.loadFooter(input)
        }
    }
    codeLocs.restoreAllCodeLocations(context)

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

internal fun loadTracePointDeep(
    input: DataInput,
    context: TraceContext,
    codeLocs: CodeLocationsContext,
    tree: TraceTree,
    consumer: TracepointConsumer
): Boolean {
    // Load tracepoint itself
    val tracePoint = loadTRTracePoint(context, input)
    consumer.tracePointRead(tree.lastOrNull(), tracePoint)
    if (tracePoint !is TRContainerTracePoint) {
        return true
    }
    // We need to load all children
    tree.add(tracePoint)
    val kind = loadObjects(input, context, codeLocs, true) { input, context, stringCache ->
        loadTracePointDeep(input, context, stringCache, tree, consumer)
    }
    when (kind) {
        ObjectKind.TRACEPOINT_FOOTER -> {
            check(tracePoint == tree.removeLast()) { "Tracepoint reading stack corruption" }
            consumer.footerStarted(tracePoint)
            tracePoint.loadFooter(input)
        }

        ObjectKind.BLOCK_END -> {
            return false
        }

        else -> {
            Logger.error { "TraceRecorder: Unexpected object kind $kind when loading tracepoints" }
            return false
        }
    }
    return true
}

internal fun loadObjects(
    input: DataInput,
    context: TraceContext,
    codeLocs: CodeLocationsContext,
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
            ObjectKind.STRING -> loadString(input, codeLocs, restore)
            ObjectKind.ACCESS_PATH -> loadAccessPath(input, codeLocs, restore)
            ObjectKind.CODE_LOCATION -> loadCodeLocation(input, codeLocs, restore)
            // Tracepoint reader returns "true" if a read is complete and "false" if it encountered the end of the block
            ObjectKind.TRACEPOINT -> if (!tracePointReader(input, context, codeLocs)) return ObjectKind.BLOCK_END
            ObjectKind.TRACEPOINT_FOOTER, // Children read or skipped by recursion into tracePointReader
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
    val id = input.readInt()
    val name = input.readUTF()
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
    val descriptor = input.readClassDescriptor()
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
    val descriptor = input.readVariableDescriptor()
    if (restore) {
        context.variablePool.restore(id, descriptor)
    }
    return id
}

internal fun loadString(
    input: DataInput,
    codeLocs: CodeLocationsContext,
    restore: Boolean
): Int {
    val id = input.readInt()
    val value = input.readUTF()
    if (restore) {
        codeLocs.loadString(id, value)
    }
    return id
}

internal fun loadAccessPath(
    input: DataInput,
    codeLocs: CodeLocationsContext,
    restore: Boolean
): Int {
    val id = input.readInt()
    val len = input.readInt()
    val locations = mutableListOf<ShallowAccessLocation>()
    repeat(len) {
        locations.add(input.readAccessLocation())
    }
    if (restore) {
        codeLocs.loadAccessPath(id, ShallowAccessPath(locations))
    }
    return id
}

internal fun loadCodeLocation(
    input: DataInput,
    codeLocs: CodeLocationsContext,
    restore: Boolean
): Int {
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

    if (restore) {
        val scl = ShallowCodeLocation(
            className = classNameId,
            methodName = methodNameId,
            fileName = fileNameId,
            lineNumber = lineNumber,
            accessPath = accessPathId,
            argumentNames = argumentNameIds,
            activeLocalsNames = activeLocalsNamesIds,
            activeLocalsKinds = activeLocalsKinds
        )
        codeLocs.loadCodeLocation(id, scl)
    }
    return id
}

internal fun checkDataHeader(input: DataInput) {
    val magic = input.readLong()
    check(magic == TRACE_MAGIC) {
        "Wrong magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}"
    }

    val version = input.readLong()
    check(version == TRACE_VERSION) {
        "Wrong version $version (expected $TRACE_VERSION)"
    }
}

internal const val INPUT_BUFFER_SIZE: Int = 16 * 1024 * 1024