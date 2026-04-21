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
import org.jetbrains.lincheck.trace.storage.RangeIndex
import org.jetbrains.lincheck.util.Logger
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
    private val postprocessor: TracePostprocessor
) : Closeable {
    private fun interface TracepointRegistrator {
        fun register(indexInParent: Int, tracePoint: TRTracePoint?, physicalOffset: Long)
    }

    constructor(baseFileName: String, postprocessor: TracePostprocessor = CompressingPostprocessor) :
            this(
                traceFileName = baseFileName,
                input = TraceDataProvider(baseFileName),
                postprocessor = postprocessor
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

    init {
        val channel = Files.newByteChannel(Path(input.dataFileName), StandardOpenOption.READ)
        dataStream = SeekableChannelBufferedInputStream(channel)
        data = SeekableDataInput(dataStream)

        try {
            checkDataHeader(data)
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

    fun readTopLevelTracePoints(): List<List<TRTracePoint>> = lock.withLock {
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
                reader = this::readTracePointWithPostprocessor,
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

    fun readRoots(): List<TRTracePoint> = lock.withLock {
        val threadTracepoints = readTopLevelTracePoints()
        return threadTracepoints.mapIndexedNotNull { threadId, tracepoints ->
            if (tracepoints.isEmpty()) {
                Logger.warn { "Thread $threadId does not contain any tracepoints" }
            } else if (tracepoints.size > 1) {
                Logger.warn { "Thread $threadId has more than one root tracepoints: ${tracepoints.size}" }
            }
            tracepoints.firstOrNull()
        }
    }

    fun loadAllChildren(parent: TRContainerTracePoint) = lock.withLock {
        if (parent.events.isEmpty()) return

        val (start, end) = callTracepointChildren[parent.eventId]
            ?: error("TRContainerTracePoint ${parent.eventId} is not found in index")

        data.seek(calculatePhysicalOffset(parent.threadId, start))

        loadTracePoints(
            threadId = parent.threadId,
            maxRead = Integer.MAX_VALUE,
            reader = this::readTracePointWithPostprocessor,
            registrator = { idx, tracePoint, _ ->
                if (tracePoint != null) parent.loadChild(idx, tracePoint)
            }
        )

        val actualFooterPos = data.position() - 1 // 1 is size of object kind
        check(actualFooterPos == calculatePhysicalOffset(parent.threadId, end)) {
            "Input contains broken data: expected Tracepoint Footer for event ${parent.eventId} at position $end, got $actualFooterPos"
        }
    }

    fun loadChild(parent: TRContainerTracePoint, childIdx: Int): Unit = lock.withLock {
        loadChildrenRange(parent, childIdx, 1)
    }

    fun loadChildrenRange(parent: TRContainerTracePoint, from: Int, count: Int) = lock.withLock {
        require(from in 0 ..< parent.events.size) { "From index $from must be in range 0 ..< ${parent.events.size}" }
        require(count in 1 ..parent.events.size - from) { "Count $count must be in range 1 .. ${parent.events.size - from}" }

        data.seek(calculatePhysicalOffset(parent.threadId, parent.getChildAddress(from)))
        loadTracePoints(
            threadId = parent.threadId,
            maxRead = count,
            reader = this::readTracePointWithPostprocessor,
            registrator = { idx, tracePoint, _ ->
                if (tracePoint != null) parent.loadChild(idx + from, tracePoint)
            }
        )
    }

    fun getChildAndRestorePosition(parent: TRContainerTracePoint, childIdx: Int): TRTracePoint? = lock.withLock {
        val oldPosition = data.position()
        loadChild(parent, childIdx)
        data.seek(oldPosition)
        return parent.events[childIdx]
    }

    private fun readTracePointWithPostprocessor(): TRTracePoint? =
        postprocessor.postprocess(
            reader = this@LazyTraceReader,
            tracePoint = readTracePointWithChildAddresses()
        )

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

        // Seek to start after magic and version
        data.seek((Long.SIZE_BYTES * 2).toLong())
    }

    private fun loadContextWithIndex(): Boolean {
        val index = input.indexStream
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
                    error("Wrong final index magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}")
                }
            } catch (t: IOException) {
                Logger.error(t) { "TraceRecorder: Error reading index for $traceFileName: ${t.message}" }
                return false
            }
        }
        return true
    }

    private fun loadContextWithoutIndex() {
        // Two Longs is header
        data.seek((Long.SIZE_BYTES * 2).toLong())
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
        val tracePoint = loadTRTracePoint(context, data)
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
            tracePoint.loadFooter(data)
        } else {
            Logger.error { "TraceRecorder: Unexpected object kind $kind when loading tracepoints" }
        }

        return tracePoint
    }

    private fun readTracePointWithChildAddresses(): TRTracePoint {
        // Load tracepoint itself
        val tracePoint = loadTRTracePoint(context, data)
        if (tracePoint !is TRContainerTracePoint) {
            return tracePoint
        }

        val (start, _) = callTracepointChildren[tracePoint.eventId]
            ?: error("Tracepoint ${tracePoint.eventId} is not known in index")

        val checkFor = calculatePhysicalOffset(tracePoint.threadId, start)
        check(data.position() == checkFor) {
            "TRContainerTracePoint ${tracePoint.eventId} has wrong start position in index: $start / $checkFor, expected ${data.position()}"
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
}