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
import org.jetbrains.lincheck.trace.storage.AddressIndex
import org.jetbrains.lincheck.trace.storage.RangeIndex
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.collections.LazyLoadableList
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.io.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.Path


/**
 * This needs a file name as it uses a seekable file channel, it is easier than seekable stream
 */
class LazyTraceReader private constructor(
    private val traceFileName: String,
    private val input: TraceDataProvider,
) : Closeable {
    private fun interface TracepointRegistrator {
        fun register(indexInParent: Int, tracePoint: TRTracePoint?, physicalOffset: Long)
    }

    constructor(baseFileName: String) :
            this(
                traceFileName = baseFileName,
                input = TraceDataProvider(baseFileName),
            )

    private var contextLoaded = false

    val context: TraceContext = TraceContext()

    val metaInfo: TraceMetaInfo? get() = input.metaInfo

    val isDiff: Boolean get() = input.metaInfo?.isDiff ?: false

    val diffThreadMap: Map<Int, Pair<Int, Int>>?
        get() {
            check(isDiff) { "Cannot provide threads id map if trace is not a diff" }
            return input.threadIdMap
        }

    val diffEventIdMap: List<Pair<Int, Int>>?
        get() {
            check(isDiff) { "Event ID map is only available for trace diffs" }
            return input.eventIdMap
        }

    val diffLeftTraceMetaInfo: TraceMetaInfo?
        get() {
            check(isDiff) { "Cannot provide events id map if trace is not a diff" }
            return input.metaInfo?.leftTraceMetaInfo
        }

    val diffRightTraceMetaInfo: TraceMetaInfo?
        get() {
            check(isDiff) { "Cannot provide right trace meta info if trace is not a diff" }
            return input.metaInfo?.rightTraceMetaInfo
        }

    private val dataStream: SeekableInputStream
    private val data: SeekableDataInput
    private val dataBlocks = mutableMapOf<Int, MutableList<DataBlock>>()
    private val callTracepointChildren = RangeIndex.create()
    private val lock = ReentrantLock()

    /**
     * Runtime that produced the trace, as read from the header, e.g. `jvm`.
     *
     * Per-runtime conventions — code addressing and type-name spellings — follow from it.
     */
    val runtime: String

    /** Offset of the first record: the header ends with a variable-length runtime string. */
    private val dataStart: Long

    init {
        val channel = Files.newByteChannel(Path(input.dataFileName), StandardOpenOption.READ)
        dataStream = SeekableChannelBufferedInputStream(channel)
        data = SeekableDataInput(dataStream)

        try {
            runtime = checkDataHeader(data)
            dataStart = data.position()
        } catch (t: Throwable) {
            data.close()
            input.close()
            throw t
        }
    }

    override fun close() {
        data.close()
        input.close()
    }

    /**
     * Reads all top-level trace points of every thread shallowly, in thread-id order:
     * container points stay flat and know nothing about their children,
     * and the reader's postprocessor is not applied.
     *
     * Children are discovered on demand through the tree API
     * (see [org.jetbrains.lincheck.trace.tree.readTraceTrees]).
     */
    fun readTopLevelTracePoints(): List<List<TRTracePoint>> =
        readTopLevelTracePoints(this::readTracePointShallow)

    private fun readTopLevelTracePoints(pointReader: () -> TRTracePoint?): List<List<TRTracePoint>> = lock.withLock {
        var start = System.currentTimeMillis()

        if (!contextLoaded) {
            loadContext()
            Logger.debug { "Context loaded in ${System.currentTimeMillis() - start} ms" }
            start = System.currentTimeMillis()
            contextLoaded = true
        }

        val threadTracepoints = mutableMapOf<Int, List<TRTracePoint>>()

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
                reader = pointReader,
                registrator = { _, tracePoint, _ ->
                    if (tracePoint != null) tracepoints.add(tracePoint)
                }
            )

            threadTracepoints[threadId] = tracepoints
        }
        Logger.debug { "Loaded top-level trace points in ${System.currentTimeMillis() - start} ms" }

        return threadTracepoints.entries
            .sortedBy { it.key }
            .map { (_, tracepoints) -> tracepoints }
    }

    /**
     * Reads per-thread root trace points shallowly:
     * container points stay flat and know nothing about their children,
     * and the reader's postprocessor is not applied.
     *
     * Children are discovered on demand through the tree API
     * (see [org.jetbrains.lincheck.trace.tree.readTraceTrees]).
     */
    fun readShallowRoots(): List<TRTracePoint> = lock.withLock {
        readTopLevelTracePoints().rootsPerThread()
    }

    private fun List<List<TRTracePoint>>.rootsPerThread(): List<TRTracePoint> =
        mapIndexedNotNull { threadId, tracepoints ->
            if (tracepoints.isEmpty()) {
                Logger.warn { "Thread $threadId does not contain any tracepoints" }
            } else if (tracepoints.size > 1) {
                Logger.warn { "Thread $threadId has more than one root tracepoints: ${tracepoints.size}" }
            }
            tracepoints.firstOrNull()
        }

    /**
     * Reads all direct children of [parent] shallowly, in one sequential scan of its children range.
     *
     * Does not modify [parent]:
     * the returned children are owned by the caller (e.g., lazily loaded tree nodes),
     * and grandchildren are not loaded.
     */
    fun loadAllChildren(parent: TRContainerTracePoint): List<TRTracePoint> = lock.withLock {
        val (start, end) = callTracepointChildren[parent.eventId]
            ?: error("TRContainerTracePoint ${parent.eventId} is not found in index")

        val children = mutableListOf<TRTracePoint>()
        data.seek(calculatePhysicalOffset(parent.threadId, start))
        loadTracePoints(
            threadId = parent.threadId,
            maxRead = Integer.MAX_VALUE,
            reader = this::readTracePointShallow,
            registrator = { _, tracePoint, _ ->
                if (tracePoint != null) children.add(tracePoint)
            }
        )

        val actualFooterPos = data.position() - 1 // 1 is size of object kind
        check(actualFooterPos == calculatePhysicalOffset(parent.threadId, end)) {
            "Input contains broken data: expected Tracepoint Footer for event ${parent.eventId} at position $end, got $actualFooterPos"
        }

        children
    }

    /**
     * Returns a lazily loaded list of the direct children of [container], loaded in one batch.
     *
     * The whole batch is read shallowly on the first element access (see [loadAllChildren]),
     * while emptiness is answered from the index alone, without loading the batch.
     *
     * Does not modify [container]:
     * the returned list is owned by the caller (e.g., a tree node that keeps it for its own lifetime).
     */
    internal fun readAllChildren(container: TRContainerTracePoint): LazyLoadableList<TRTracePoint> =
        LazyLoadableList(
            loadAll = { loadAllChildren(container) },
            computeIsEmpty = { !hasChildren(container) },
        )

    /**
     * Returns a lazily loaded list of the direct children of [container].
     *
     * The child addresses are discovered by one skim of the children range,
     * performed lazily and memoized on the first access to the list's size or an element;
     * each child is then materialized shallowly on first access,
     * and after an unload, the next access re-reads the child from the trace data.
     *
     * Does not modify [container]:
     * the returned list is owned by the caller (e.g., a tree node that keeps it for its own lifetime).
     */
    internal fun readChildren(container: TRContainerTracePoint): LazyLoadableList<TRTracePoint> {
        val addresses: Lazy<List<Long>> = lazy { readChildAddresses(container) }
        return LazyLoadableList(
            computeSize = { addresses.value.size },
            load = { index ->
                readTracePointAt(container.threadId, addresses.value[index])
                    ?: error("Child $index of trace point ${container.eventId} cannot be loaded")
            }
        )
    }

    /**
     * Whether [container] has any direct children.
     *
     * Answered from the index alone — no trace data is read.
     */
    private fun hasChildren(container: TRContainerTracePoint): Boolean = lock.withLock {
        val (start, end) = callTracepointChildren[container.eventId]
            ?: error("TRContainerTracePoint ${container.eventId} is not found in index")
        start != end
    }

    /**
     * Discovers the direct children of [container] by skimming its children range in the trace data.
     *
     * The returned addresses are logical offsets to be passed to [readTracePointAt];
     * they never leave the reader; external users go through [readChildren].
     */
    private fun readChildAddresses(container: TRContainerTracePoint): List<Long> = lock.withLock {
        val (start, end) = callTracepointChildren[container.eventId]
            ?: error("TRContainerTracePoint ${container.eventId} is not found in index")

        val addresses = AddressIndex.create()
        data.seek(calculatePhysicalOffset(container.threadId, start))
        loadTracePoints(
            threadId = container.threadId,
            maxRead = Integer.MAX_VALUE,
            reader = this::readTracePointShallow,
            registrator = { _, _, physicalOffset ->
                addresses.add(calculateLogicalOffset(container.threadId, physicalOffset))
            }
        )
        addresses.finishWrite()

        val actualFooterPos = data.position() - 1 // 1 is size of object kind
        check(actualFooterPos == calculatePhysicalOffset(container.threadId, end)) {
            "Input contains broken data: expected Tracepoint Footer for event ${container.eventId} at position $end, got $actualFooterPos"
        }

        addresses
    }

    /**
     * Reads the single trace point at [address] (a logical offset obtained from [readChildAddresses]) shallowly:
     * its children are not loaded, no parent link is set, and the reader's postprocessor is not applied.
     */
    private fun readTracePointAt(threadId: Int, address: Long): TRTracePoint? = lock.withLock {
        data.seek(calculatePhysicalOffset(threadId, address))
        var tracePoint: TRTracePoint? = null
        loadTracePoints(
            threadId = threadId,
            maxRead = 1,
            reader = this::readTracePointShallow,
            registrator = { _, point, _ -> tracePoint = point }
        )
        tracePoint
    }

    private fun loadTracePoints(
        threadId: Int,
        maxRead: Int,
        reader: () -> TRTracePoint?,
        registrator: TracepointRegistrator
    ) {
        val blocks = dataBlocks[threadId] ?: error("No data blocks for Thread $threadId")
        var idx = 0
        while (true) {
            var kind = loadObjects(data, context, restore = false) { _, _ ->
                val tracePointOffset = data.position() - 1 // account for Kind
                val tracePoint = reader()
                if (tracePoint != null) {
                    registrator.register(idx++, tracePoint, tracePointOffset)
                }
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
            check(blockIdx != null) { "Thread $threadId doesn't contain physical offset $physicalOffset" }
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
        val index = input.indexStream
        if (index == null) {
            Logger.warn { "TraceRecorder: No index file is given for $traceFileName: read whole data file to re-create context" }
            loadContextWithoutIndex()
        } else if (!loadContextWithIndex()) {
            Logger.warn { "TraceRecorder: Index file for $traceFileName is corrupted: read whole data file to re-create context" }
            context.clear()
            dataBlocks.clear()
            loadContextWithoutIndex()
        }

        // Seek to start after the header
        data.seek(dataStart)
    }

    private fun loadContextWithIndex(): Boolean {
        val index = input.indexStream
        if (index == null) return false
        index.use { index ->
            try {
                // Check format
                index.checkTraceIndexHeader()

                var objNum = 0
                var tps = 0
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
                            ObjectKind.THREAD_NAME -> loadThreadName(data, context, restore = true)
                            ObjectKind.CLASS_DESCRIPTOR -> loadClassDescriptor(data, context, restore = true)
                            ObjectKind.METHOD_DESCRIPTOR -> loadMethodDescriptor(data, context, restore = true)
                            ObjectKind.FIELD_DESCRIPTOR -> loadFieldDescriptor(data, context, restore = true)
                            ObjectKind.VARIABLE_DESCRIPTOR -> loadVariableDescriptor(data, context, restore = true)
                            ObjectKind.STRING -> loadString(data, context, restore = true)
                            ObjectKind.ACCESS_PATH -> loadAccessPath(data, context, restore = true)
                            ObjectKind.CODE_LOCATION -> loadCodeLocation(data, context, restore = true)
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
                val magicEnd = index.readLong()
                if (magicEnd != INDEX_MAGIC) {
                    error("Wrong final index magic 0x${magicEnd.toString(16)}, expected 0x${INDEX_MAGIC.toString(16)}")
                }
            } catch (t: IOException) {
                Logger.error(t) { "TraceRecorder: Error reading index for $traceFileName: ${t.message}" }
                return false
            }
        }
        return true
    }

    private fun loadContextWithoutIndex() {
        data.seek(dataStart)
        loadAllObjectsDeep(
            input = data,
            context = context,
            tracepointConsumer = object : TracepointConsumer {
                override fun tracePointRead(
                    parent: TRContainerTracePoint?,
                    tracePoint: TRTracePoint
                ) {
                    if (tracePoint is TRContainerTracePoint) {
                        // We are in the last saved block in
                        val childrenStart = calculateLogicalOffset(tracePoint.threadId, data.position())
                        callTracepointChildren.addStart(tracePoint.eventId, childrenStart)
                    }
                }

                override fun footerStarted(tracePoint: TRContainerTracePoint) {
                    // -1 is here because Kind is already read
                    val childrenEnd = calculateLogicalOffset(tracePoint.threadId, data.position() - 1)
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
        val tracePoint = data.readTRTracePoint(context)
        if (tracePoint !is TRContainerTracePoint) {
            return tracePoint
        }

        val (start, end) = callTracepointChildren[tracePoint.eventId]
            ?: error("TRContainerTracePoint ${tracePoint.eventId} is not found in index")

        val checkFor = calculatePhysicalOffset(tracePoint.threadId, start)
        check(data.position() == checkFor) {
            "TRContainerTracePoint ${tracePoint.eventId} has wrong start position in index: $start / $checkFor, expected ${data.position()}"
        }

        val skipTo = calculatePhysicalOffset(tracePoint.threadId, end)
        data.seek(skipTo)

        val kind = data.readKind()
        if (kind == ObjectKind.TRACEPOINT_FOOTER) {
            // TODO: loading footer could also be extracted to the DefaultTracePointSerializer (name should change as well)
            tracePoint.loadFooter(data)
        } else {
            Logger.error { "TraceRecorder: Unexpected object kind $kind when loading tracepoints" }
        }

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
}