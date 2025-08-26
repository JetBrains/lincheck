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

import org.jetbrains.lincheck.descriptors.AccessLocation
import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.util.Logger
import java.io.Closeable
import java.io.DataInput
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import org.jetbrains.lincheck.descriptors.CodeLocation

private const val INPUT_BUFFER_SIZE: Int = 16 * 1024 * 1024

private typealias CallStack = MutableList<TRMethodCallTracePoint>
private typealias TracePointReader = (DataInput, TraceContext, CodeLocationsContext) -> Boolean

private interface TracepointConsumer {
    fun tracePointRead(parent: TRMethodCallTracePoint?, tracePoint: TRTracePoint)
    fun footerStarted(tracePoint: TRMethodCallTracePoint) {}
}

private interface BlockConsumer {
    fun blockStarted(threadId: Int) {}
    fun blockEnded(threadId: Int) {}
}

private class DataBlock(
    physicalStart: Long,
    physicalEnd: Long,
    accDataSize: Long
) {
    constructor(start: Long, accDataSize: Long) : this(start, Long.MAX_VALUE, accDataSize)

    val physicalStart: Long
    var physicalEnd: Long
        private set

    /**
     * Accumulated data size: sum of all data blocks' sizes in this thread before this block.
     */
    val accDataSize: Long

    val physicalDataStart: Long get() = physicalStart + BLOCK_HEADER_SIZE
    val logicalDataStart: Long get() = accDataSize
    val logicalDataEnd: Long get() = accDataSize + dataSize

    val size: Long get() = physicalEnd - physicalStart
    val dataSize: Long get() = physicalEnd - physicalStart - BLOCK_HEADER_SIZE

    init {
        require(physicalStart >= 0) { "start must be non-negative" }
        require(physicalStart < physicalEnd)  { "block cannot be empty" }
        require(accDataSize >= 0) { "accumulated data size cannot be negative" }
        this.physicalStart = physicalStart
        this.physicalEnd = physicalEnd
        this.accDataSize = accDataSize
    }

    fun coversPhysicalOffset(offset: Long): Boolean = offset in physicalStart ..< physicalEnd

    fun coversLogicalOffset(offset: Long): Boolean = offset in logicalDataStart ..< logicalDataStart + dataSize

    /**
     * For usage with [List.binarySearch]
     *
     * Function that returns zero when called on the list element being searched.
     * On the elements coming before the target element, the function must return negative values;
     * on the elements coming after the target element, the function must return positive values.
     */
    fun compareWithPhysicalOffset(offset: Long): Int =
        if (offset < physicalStart) +1
        else if (offset >= physicalEnd) -1
        else 0

    fun compareWithLogicalOffset(offset: Long): Int =
        if (offset < accDataSize) +1
        else if (offset >= accDataSize + dataSize) -1
        else 0

    fun updateEnd(newPhysicalEnd: Long) {
        require(physicalStart < newPhysicalEnd)  { "block cannot be empty" }
        check(physicalEnd == Long.MAX_VALUE) { "block cannot be updated twice"}
        physicalEnd = newPhysicalEnd
    }
}

private typealias BlockList = MutableList<DataBlock>

private fun BlockList.addNewPartialBlock(start: Long) {
    require(isEmpty() || last().physicalEnd < start) {
        "Start offsets of blocks in the list must increase: last block ends at ${last().physicalEnd}, new starts at $start "
    }
    add(DataBlock(start, dataSize()))
}

private fun BlockList.addNewBlock(start: Long, end: Long) {
    require(isEmpty() || last().physicalEnd < start) {
        "Start offsets of blocks in the list must increase: last block ends at ${last().physicalEnd}, new starts at $start "
    }
    add(DataBlock(start, end,dataSize()))
}

private fun BlockList.fixLastBlock(end: Long) {
    check(isNotEmpty()) { "Cannot fix last block in empty list" }
    last().updateEnd(end)
}

private fun BlockList.dataSize(): Long {
    return if (isEmpty()) 0 else last().accDataSize + last().dataSize
}


internal data class ShallowCodeLocation(
    val className: Int,
    val methodName: Int,
    val fileName: Int,
    val lineNumber: Int,
    val accessPath: Int
)

/**
 * This class is used to load code locations without referring strings too early.
 *
 * It is possible, that code location ([StackTraceElement]) can be loaded before
 * its strings are loaded due to data blocks serialization order.
 */
internal class CodeLocationsContext {
    private val stringCache: MutableList<String?> = ArrayList()
    private val accessPathsCache: MutableList<AccessPath?> = ArrayList()
    private val shallowCodeLocations: MutableList<ShallowCodeLocation?> = ArrayList()

    fun loadString(id: Int, value: String): Unit = load(stringCache, id, value)

    fun loadAccessPath(id: Int, value: AccessPath): Unit = load(accessPathsCache, id, value)

    fun getLoadedAccessPath(id: Int): AccessPath = accessPathsCache[id] ?: error("Referenced access path not loaded: $id")

    fun loadCodeLocation(id: Int, value: ShallowCodeLocation): Unit = load(shallowCodeLocations, id, value)

    // TODO: add shallow access path here as well and its restoring

    fun restoreAllCodeLocations(context: TraceContext) {
        shallowCodeLocations.forEachIndexed { id, value ->
            if (value != null) {
                val stackTraceElement = StackTraceElement(
                    /* declaringClass = */ stringCache[value.className] ?: "<unknown class>",
                    /* methodName = */ stringCache[value.methodName] ?: "<unknown method>",
                    /* fileName = */ stringCache[value.fileName] ?: "<unknown file>",
                    /* lineNumber = */ value.lineNumber
                )
                val accessPath = if (value.accessPath == -1) null else accessPathsCache[value.accessPath]
                val location = CodeLocation(stackTraceElement, accessPath)
                context.restoreCodeLocation(id, location)
            }
        }
    }

    private fun <T> load(container: MutableList<T?>, id: Int, value: T) {
        while (container.size <= id) {
            container.add(null)
        }
        container[id] = value
    }
}


/**
 * This needs a file name as it uses a seekable file channel, it is easier than seekable stream
 */
class LazyTraceReader(
    private val dataFileName: String,
    private val index: DataInputStream?,
    private val postprocessor: TracePostprocessor
) : Closeable {

    private fun interface TracepointRegistrator {
        fun register(indexInParent: Int, tracePoint: TRTracePoint, physicalOffset: Long)
    }

    constructor(baseFileName: String, postprocessor: TracePostprocessor = CompressingPostprocessor) :
            this(
                dataFileName = baseFileName,
                index = wrapStream(openExistingFile(baseFileName + INDEX_FILENAME_SUFFIX)),
                postprocessor = postprocessor
            )

    private fun readTracePointWithPostprocessor(): TRTracePoint =
        postprocessor.postprocess(
            reader = this@LazyTraceReader,
            tracePoint = readTracePointWithChildAddresses()
        )

    // TODO: Create new
    val context: TraceContext = TRACE_CONTEXT

    private val dataStream: SeekableInputStream
    private val data: SeekableDataInput
    private val dataBlocks = mutableMapOf<Int, MutableList<DataBlock>>()
    private val callTracepointChildren = RangeIndex.create()

    init {
        val channel = Files.newByteChannel(Path(dataFileName), StandardOpenOption.READ)
        dataStream = SeekableChannelBufferedInputStream(channel)
        data = SeekableDataInput(dataStream)

        try {
            checkDataHeader(data)
        } catch (t: Throwable) {
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
        var start = System.currentTimeMillis()
        loadContext()
        Logger.debug { "Context loaded in ${System.currentTimeMillis() - start} ms" }
        start = System.currentTimeMillis()

        val roots = mutableMapOf<Int, TRTracePoint>()

        dataBlocks.forEach {
            val (threadId, blocks) = it
            data.seek(blocks.first().physicalStart)
            val kind = data.readKind()
            check(kind == ObjectKind.BLOCK_START) { "Thread $threadId block 0 has wrong start: $kind" }
            val blockId = data.readInt()
            check(blockId == threadId) { "Thread $threadId block 0 has wrong idt: $blockId" }

            val tracepoints = mutableListOf<TRTracePoint>()
            loadTracePoints(
                threadId = threadId,
                maxRead = Integer.MAX_VALUE,
                reader = this::readTracePointWithPostprocessor,
                registrator = { _, tracePoint, _ ->
                    tracepoints.add(tracePoint)
                }
            )
            if (tracepoints.isEmpty()) {
                System.err.println("Thread $threadId doesn't write any tracepoints")
            } else {
                if (tracepoints.size > 1) {
                    System.err.println("Thread $threadId wrote too many root tracepoints: ${tracepoints.size}")
                }
                roots[threadId] = tracepoints.first()
            }
        }
        Logger.debug { "Roots loaded in ${System.currentTimeMillis() - start} ms" }

        return roots.entries.sortedBy { it.key }.map { (_, tracePoint) -> tracePoint }
    }

    fun loadAllChildren(parent: TRMethodCallTracePoint) {
        val (start, end) = callTracepointChildren[parent.eventId]
            ?: error("TRMethodCallTracePoint ${parent.eventId} is not found in index")

        data.seek(calculatePhysicalOffset(parent.threadId, start))

        loadTracePoints(
            threadId = parent.threadId,
            maxRead = Integer.MAX_VALUE,
            reader = this::readTracePointWithPostprocessor,
            registrator = { idx, tracePoint, _ ->
                parent.loadChild(idx, tracePoint)
            }
        )

        val actualFooterPos = data.position() - 1 // 1 is size of object kind
        check(actualFooterPos == calculatePhysicalOffset(parent.threadId, end)) {
            "Input contains broken data: expected Tracepoint Footer for event ${parent.eventId} at position $end, got $actualFooterPos"
        }
    }

    fun loadChild(parent: TRMethodCallTracePoint, childIdx: Int): Unit = loadChildrenRange(parent, childIdx, 1)

    fun loadChildrenRange(parent: TRMethodCallTracePoint, from: Int, count: Int) {
        require(from in 0 ..< parent.events.size) { "From index $from must be in range 0..<${parent.events.size}" }
        require(count in 1 .. parent.events.size - from) { "Count $count must be in range 1..${parent.events.size - from}" }

        data.seek(calculatePhysicalOffset(parent.threadId, parent.getChildAddress(from)))
        loadTracePoints(
            threadId = parent.threadId,
            maxRead = count,
            reader = this::readTracePointWithPostprocessor,
            registrator = { idx, tracePoint, _ ->
                parent.loadChild(idx + from, tracePoint)
            }
        )
    }

    fun getChildAndRestorePosition(parent: TRMethodCallTracePoint, childIdx: Int): TRTracePoint? {
        val oldPosition = data.position()
        loadChild(parent, childIdx)
        data.seek(oldPosition)
        return parent.events[childIdx]
    }

    private fun loadTracePoints(threadId: Int, maxRead: Int, reader: () -> TRTracePoint, registrator: TracepointRegistrator) {
        val blocks = dataBlocks[threadId] ?: error("No data blocks for Thread $threadId")
        var idx = 0
        while (true) {
            var kind = loadObjects(data, context, CodeLocationsContext(), false) { _, _, _ ->
                val tracePointOffset = data.position() - 1 // account for Kind
                val tracePoint = reader()
                registrator.register(idx++, tracePoint, tracePointOffset)
                idx < maxRead
            }
            if (idx == maxRead) {
                break
            }
            if (kind == ObjectKind.TRACEPOINT_FOOTER) {
                break
            } else if (kind != ObjectKind.BLOCK_END) {
                error("Unexpected object kind $kind when reading tracepoints")
            }

            // Find the next block, -2 to take the size of BLOCK_END into account, and that block end is exclusive
            // point to last data byte of block, as current position points after BLOCK_END byte
            val physicalOffset = data.position() - 2
            val blockIdx = findBlockByPhysicalOffset(threadId, physicalOffset)
            check(blockIdx != null) { "Thread $threadId doesn't contain physical offset $physicalOffset"}
            if (blockIdx + 1 == blocks.size) {
                // Thread ended, Ok
                return
            }
            val block = blocks.getOrNull(blockIdx + 1)
                ?: error("Thread $threadId doesn't have enough data blocks")

            data.seek(block.physicalStart)
            kind = data.readKind()
            check(kind == ObjectKind.BLOCK_START) { "Thread $threadId block $blockIdx has invalid start: $kind" }
            val id = data.readInt()
            check(id == threadId) { "Thread $threadId block $blockIdx has invalid id: $id" }
            // Ready to continue reading
        }
    }

    private fun loadContext() {
        if (index == null) {
            System.err.println("TraceRecorder: No index file is given for ${this.dataFileName}: read whole data file to re-create context")
            loadContextWithoutIndex()
        } else if (!loadContextWithIndex()) {
            System.err.println("TraceRecorder: Index file for $dataFileName id corrupted: read whole data file to re-create context")
            context.clear()
            dataBlocks.clear()
            loadContextWithoutIndex()
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
                check(magic == INDEX_MAGIC) {
                    "Wrong index magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}"
                }

                val version = index.readLong()
                check(version == TRACE_VERSION) {
                    "Wrong index version $version, expected $TRACE_VERSION"
                }

                var objNum = 0
                var tps = 0
                val codeLocs = CodeLocationsContext()
                while (true) {
                    val kind = index.readKind()
                    val id = index.readInt()
                    val start = index.readLong()
                    val end = index.readLong()

                    if (kind == ObjectKind.EOF) break

                    if (kind == ObjectKind.TRACEPOINT) {
                        tps++
                        callTracepointChildren.addRange(id, start, end)
                    } else {
                        // Check kind
                        data.seek(start)
                        val dataKind = data.readKind()
                        check(dataKind == kind) {
                            "Object $objNum: expected $kind but datafile has $dataKind"
                        }
                        val dataId = when (kind) {
                            ObjectKind.CLASS_DESCRIPTOR -> loadClassDescriptor(data, context, true)
                            ObjectKind.METHOD_DESCRIPTOR -> loadMethodDescriptor(data, context, true)
                            ObjectKind.FIELD_DESCRIPTOR -> loadFieldDescriptor(data, context, true)
                            ObjectKind.VARIABLE_DESCRIPTOR -> loadVariableDescriptor(data, context, true)
                            ObjectKind.STRING -> loadString(data, codeLocs, true)
                            ObjectKind.ACCESS_PATH -> loadAccessPath(data, context, codeLocs, true)
                            ObjectKind.CODE_LOCATION -> loadCodeLocation(data, codeLocs, true)
                            ObjectKind.BLOCK_START -> {
                                val list = dataBlocks.computeIfAbsent(id) { mutableListOf() }
                                list.addNewBlock(start, end)
                                // Read id from data for check
                                data.readInt()
                            }
                            // Kotlin complains without these branches, though they are unreachable
                            ObjectKind.TRACEPOINT,
                            ObjectKind.EOF -> -1
                            // Cannot be in index
                            ObjectKind.BLOCK_END,
                            ObjectKind.TRACEPOINT_FOOTER -> error("Object $objNum has unexpected kind $kind")
                        }
                        check(id == dataId) {
                            "Object $objNum of kind $kind: expected $id but datafile has $dataId"
                        }
                    }
                    objNum++
                }
                callTracepointChildren.finishIndex()
                codeLocs.restoreAllCodeLocations(context)
                val magicEnd = index.readLong()
                if (magicEnd != INDEX_MAGIC) {
                    error("Wrong final index magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}")
                }
            } catch (t: IOException) {
                System.err.println("TraceRecorder: Error reading index for $dataFileName: ${t.message}")
                return false
            }
        }
        return true
    }

    private fun loadContextWithoutIndex() {
        val context = TRACE_CONTEXT
        // Two Longs is header
        data.seek((Long.SIZE_BYTES * 2).toLong())
        loadAllObjectsDeep(
            input = data,
            context = context,
            tracepointConsumer = object : TracepointConsumer {
                override fun tracePointRead(
                    parent: TRMethodCallTracePoint?,
                    tracePoint: TRTracePoint
                ) {
                    if (tracePoint is TRMethodCallTracePoint) {
                        // We are in the last saved block in
                        val childrenStart = calculateLogicalOffset(tracePoint.threadId,data.position())
                        callTracepointChildren.addStart(tracePoint.eventId, childrenStart)
                    }
                }

                override fun footerStarted(tracePoint: TRMethodCallTracePoint) {
                    // -1 is here because Kind is already read
                    val childrenEnd = calculateLogicalOffset(tracePoint.threadId,data.position() - 1)
                    callTracepointChildren.setEnd(tracePoint.eventId, childrenEnd)
                }
            },
            blockConsumer = object : BlockConsumer {
                private var blockStart: Long = 0L

                override fun blockStarted(threadId: Int) {
                    // 5 bytes for the header which is read already
                    blockStart = data.position() - BLOCK_HEADER_SIZE
                    dataBlocks.computeIfAbsent(threadId) { mutableListOf() }.addNewPartialBlock(blockStart)
                }

                override fun blockEnded(threadId: Int) {
                    val endPos = data.position() - BLOCK_FOOTER_SIZE // 1 byte for read kind
                    dataBlocks[threadId]?.fixLastBlock(endPos)
                }

            }
        )
        callTracepointChildren.finishIndex()
    }

    private fun readTracePointShallow(): TRTracePoint {
        // Load tracepoint itself
        val tracePoint = loadTRTracePoint(data)
        if (tracePoint !is TRMethodCallTracePoint) {
            return tracePoint
        }

        val (start, end) = callTracepointChildren[tracePoint.eventId]
            ?: error("TRMethodCallTracePoint ${tracePoint.eventId} is not found in index")

        val checkFor = calculatePhysicalOffset(tracePoint.threadId, start)
        check(data.position() == checkFor) {
            "TRMethodCallTracePoint ${tracePoint.eventId} has wrong start position in index: $start / $checkFor, expected ${data.position()}"
        }

        val skipTo = calculatePhysicalOffset(tracePoint.threadId, end)
        data.seek(skipTo)

        val kind = data.readKind()
        if (kind == ObjectKind.TRACEPOINT_FOOTER) {
            tracePoint.loadFooter(data)
        } else {
            System.err.println("TraceRecorder: Unexpected object kind $kind when loading tracepoints")
        }

        return tracePoint
    }

    private fun readTracePointWithChildAddresses(): TRTracePoint {
        // Load tracepoint itself
        val tracePoint = loadTRTracePoint(data)
        if (tracePoint !is TRMethodCallTracePoint) {
            return tracePoint
        }

        val (start, _) = callTracepointChildren[tracePoint.eventId]
            ?: error("Tracepoint ${tracePoint.eventId} is not known in index")

        val checkFor = calculatePhysicalOffset(tracePoint.threadId, start)
        check(data.position() == checkFor) {
            "TRMethodCallTracePoint ${tracePoint.eventId} has wrong start position in index: $start / $checkFor, expected ${data.position()}"
        }

        // Read tracepoints truly shallow
        loadTracePoints(
            threadId = tracePoint.threadId,
            maxRead = Integer.MAX_VALUE,
            reader = this::readTracePointShallow,
            registrator = { _, _, physicalOffset ->
                tracePoint.addChildAddress(calculateLogicalOffset(tracePoint.threadId, physicalOffset))
            }
        )
        // Kind is guaranteed by loadTracePoints()
        tracePoint.loadFooter(data)

        return tracePoint
    }

    private fun calculatePhysicalOffset(threadId: Int, logicalOffset: Long): Long {
        val blocks = dataBlocks[threadId] ?: error("ThreadId $threadId is not found in block list")
        val blockIdx = blocks.binarySearch { it.compareWithLogicalOffset(logicalOffset) }
        check(blockIdx >= 0) { "Thread $threadId doesn't have data at logical offset $logicalOffset" }
        val block = blocks[blockIdx]
        return block.physicalDataStart + logicalOffset - block.accDataSize
    }

    private fun calculateLogicalOffset(threadId: Int, physicalOffset: Long): Long {
        val blocks = dataBlocks[threadId] ?: error("ThreadId $threadId is not found in block list")
        val blockIdx = blocks.binarySearch { it.compareWithPhysicalOffset(physicalOffset) }
        check(blockIdx >= 0) { "Thread $threadId doesn't have data at physical offset $physicalOffset" }
        val block = blocks[blockIdx]
        return block.accDataSize + physicalOffset - block.physicalDataStart
    }

    private fun findBlockByPhysicalOffset(threadId: Int, physicalOffset: Long): Int? {
        val blocks = dataBlocks[threadId] ?: error("ThreadId $threadId is not found in block list")
        val blockIdx = blocks.binarySearch { it.compareWithPhysicalOffset(physicalOffset) }
        return if (blockIdx < 0) null else blockIdx
    }

    private companion object {
        fun wrapStream(input: InputStream?): DataInputStream? {
            if (input == null) return null
            return DataInputStream(input.buffered(INPUT_BUFFER_SIZE))
        }
    }
}

data class TraceWithContext(
    val context: TraceContext,
    val roots: List<TRTracePoint>
)

fun loadRecordedTrace(inp: InputStream): TraceWithContext {
    DataInputStream(inp.buffered(INPUT_BUFFER_SIZE)).use { input ->
        checkDataHeader(input)

        // TODO: Create empty fresh context
        val context = TRACE_CONTEXT
        val roots = mutableMapOf<Int, MutableList<TRTracePoint>>()

        loadAllObjectsDeep(
            input = input,
            context = context,
            tracepointConsumer = object : TracepointConsumer {
                override fun tracePointRead(
                    parent: TRMethodCallTracePoint?,
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
                System.err.println("TraceRecorder: Thread #${it.key} contains multiple top-level calls")
            }
        }

        return TraceWithContext(context, roots.values.map { it.first() })
    }
}

private fun loadAllObjectsDeep(
    input: DataInputStream,
    context: TraceContext,
    tracepointConsumer: TracepointConsumer,
    blockConsumer: BlockConsumer
) {
    val codeLocs = CodeLocationsContext()
    val stacks = mutableMapOf<Int, MutableList<TRMethodCallTracePoint>>()
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

        val stack = stacks.computeIfAbsent(threadId) { mutableListOf() }

        // Read objects and tracepoints from this block till it ends.
        // Unwind the stack manually, if needed
        while (true) {
            val kind = loadObjects(input, context, codeLocs, true) { input, context, codeLocs ->
                loadTracePointDeep(input, context, codeLocs, stack, tracepointConsumer)
            }
            if (kind == ObjectKind.BLOCK_END) {
                blockConsumer.blockEnded(threadId)
                break
            }
            check(kind == ObjectKind.TRACEPOINT_FOOTER) {
                "Unexpected object kind $kind, expected TRACEPOINT_FOOTER, broken file"
            }
            check (stack.isNotEmpty()) { "Stack underflow" }

            val tracePoint = stack.removeLast()
            tracepointConsumer.footerStarted(tracePoint)
            tracePoint.loadFooter(input)
        }
    }
    codeLocs.restoreAllCodeLocations(context)

    if (!seenEOF) {
        System.err.println("TraceRecorder: no EOF record at the end of the file")
    }
    // Check that all stacks are empty
    stacks.forEach {
        if (!it.value.isEmpty()) {
            System.err.println("TraceRecorder: Thread #${it.key} contains unfinished method calls")
        }
    }
}

private fun loadTracePointDeep(
    input: DataInput,
    context: TraceContext,
    codeLocs: CodeLocationsContext,
    stack: CallStack,
    consumer: TracepointConsumer
): Boolean {
    // Load tracepoint itself
    val tracePoint = loadTRTracePoint(input)
    consumer.tracePointRead(stack.lastOrNull(), tracePoint)
    if (tracePoint !is TRMethodCallTracePoint) {
        return true
    }
    // We need to load all children
    stack.add(tracePoint)
    val kind = loadObjects(input, context, codeLocs, true) { input, context, stringCache ->
        loadTracePointDeep(input, context, stringCache, stack, consumer)
    }
    when (kind) {
        ObjectKind.TRACEPOINT_FOOTER -> {
            check(tracePoint == stack.removeLast()) { "Tracepoint reading stack corruption" }
            consumer.footerStarted(tracePoint)
            tracePoint.loadFooter(input)
        }
        ObjectKind.BLOCK_END -> {
            return false
        }
        else -> {
            System.err.println("TraceRecorder: Unexpected object kind $kind when loading tracepoints")
            return false
        }
    }
    return true
}

private fun loadObjects(
    input: DataInput,
    context: TraceContext,
    codeLocs: CodeLocationsContext,
    restore: Boolean,
    tracePointReader: TracePointReader
): ObjectKind {
    while (true) {
        when (val kind = input.readKind()) {
            ObjectKind.CLASS_DESCRIPTOR -> loadClassDescriptor(input, context, restore)
            ObjectKind.METHOD_DESCRIPTOR -> loadMethodDescriptor(input, context, restore)
            ObjectKind.FIELD_DESCRIPTOR -> loadFieldDescriptor(input, context, restore)
            ObjectKind.VARIABLE_DESCRIPTOR -> loadVariableDescriptor(input, context, restore)
            ObjectKind.STRING -> loadString(input, codeLocs, restore)
            ObjectKind.ACCESS_PATH -> loadAccessPath(input, context, codeLocs, restore)
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

private fun loadClassDescriptor(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
): Int {
    val id = input.readInt()
    val descriptor = input.readClassDescriptor()
    if (restore) {
        context.restoreClassDescriptor(id, descriptor)
    }
    return id
}

private fun loadMethodDescriptor(
    input: DataInput,
    context: TraceContext,
    restore: Boolean
): Int {
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
): Int {
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
): Int {
    val id = input.readInt()
    val descriptor = input.readVariableDescriptor()
    if (restore)
        context.restoreVariableDescriptor(id, descriptor)
    return id
}

private fun loadString(
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

private fun loadAccessPath(
    input: DataInput,
    context: TraceContext,
    codeLocs: CodeLocationsContext,
    restore: Boolean
): Int {
    val id = input.readInt()
    val len = input.readInt()
    val locations = mutableListOf<AccessLocation>()
    repeat(len) {
        val location = input.readAccessLocation(context, codeLocs)
        locations.add(location)
    }
    if (restore) {
        codeLocs.loadAccessPath(id, AccessPath(locations))
    }
    return id
}

private fun loadCodeLocation(
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

    if (restore) {
        val scl = ShallowCodeLocation(
            className = classNameId,
            methodName = methodNameId,
            fileName = fileNameId,
            lineNumber = lineNumber,
            accessPath = accessPathId
        )
        codeLocs.loadCodeLocation(id, scl)
    }
    return id
}

private fun checkDataHeader(input: DataInput) {
    val magic = input.readLong()
    check(magic == TRACE_MAGIC) {
        "Wrong magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}"
    }

    val version = input.readLong()
    check(version == TRACE_VERSION) {
        "Wrong version $version (expected $TRACE_VERSION)"
    }
}
