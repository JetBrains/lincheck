/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.diff

import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.*
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.collections.component1
import kotlin.collections.component2

internal data class ThreadMapElement(val name: String, val startThread: Boolean, val leftIdx: Int, val rightIdx: Int)

fun diffTwoTraces(left: LazyTraceReader, right: LazyTraceReader, outputBaseName: String) =
    diffTwoTraces(left, right, outputBaseName, TraceDiffOptions.DEFAULT)

fun diffTwoTraces(left: LazyTraceReader, right: LazyTraceReader, outputBaseName: String, options: TraceDiffOptions) {
    if (!options.forceDiff) {
        require(!left.isDiff) { "Cannot diff other diffs: left trace is diff" }
        require(!right.isDiff) { "Cannot diff other diffs: right trace is diff" }
    }
    
    val diffStartTime = System.currentTimeMillis()

    // Load all left and right roots.
    val leftRoots = left.readRoots()
    val rightRoots = right.readRoots()

    // Match threads by name for now
    // Try to match same names
    val threadMap = matchThreads(left, leftRoots, right, rightRoots, options)

    // Check map, if needed
    if (options.unmatchedThreadsBehavior == TraceDiffOptions.UnmatchedThreadsBehavior.ERROR) {
        threadMap.forEach {
            if (it.rightIdx < 0) {
                val lid = leftRoots[it.leftIdx].threadId
                throw IllegalStateException("Thread #${it.leftIdx} (id=$lid, name=${it.name}) from left trace was not matched with any thread in right trace.")
            }
            if (it.leftIdx < 0) {
                val rid = rightRoots[it.rightIdx].threadId
                throw IllegalStateException("Thread #${it.rightIdx} (id=$rid, name=${it.name}) from right trace was not matched with any thread in left trace.")
            }
        }
    }

    // Save thread map to temporary file.
    val threadMapFile = saveThreadMap(threadMap)

    // Prepare writer to save result
    val outputContext = TraceContext()
    val (outputData, outputIndex) = openNewStandardDataAndIndex(outputBaseName)
    val output = DirectTraceWriter(outputData, outputIndex, outputContext)

    val idMapFile = File.createTempFile("trace-diff-", ".$ID_MAP_FILENAME_EXT")
        .also { it.deleteOnExit() }
    val idMapStream = DataOutputStream(FileOutputStream(idMapFile).buffered(OUTPUT_BUFFER_SIZE))

    val cloner = TracePointCloner(output.context,idMapStream)
    output.use {
        if (options.diffOnlyStartThreads) {
            // Diff only thread with eventIds = 0
            val threadMapping = threadMap.getOrNull(0)
            if (threadMapping == null || !threadMapping.startThread) {
                throw IllegalStateException("Only start thread diff is requested, but no start threads pair was found.")
            }
            cloner.setThread(0)
            output.startNewRoot(0)
            diffOneThread(
                cloner = cloner,
                output = output,
                outputThreadId = 0,
                name = threadMapping.name,
                left = left,
                leftRoot = leftRoots[threadMapping.leftIdx],
                right = right,
                rightRoot = rightRoots[threadMapping.rightIdx],
            )
            output.writeThreadName(0, output.context.getThreadName(0))
            output.endRoot()
        } else {
            // Ok, we have all threads matched, work on them one by one.
            threadMap.forEachIndexed { outputThreadId, (name, _, leftThreadIdx, rightThreadIdx) ->

                // Skip this mapping if needed
                if (options.unmatchedThreadsBehavior == TraceDiffOptions.UnmatchedThreadsBehavior.SKIP && (leftThreadIdx < 0 || rightThreadIdx < 0)) {
                    return@forEachIndexed
                }

                // Configure cloner for this target thread
                cloner.setThread(outputThreadId)
                output.startNewRoot(outputThreadId)

                if (leftThreadIdx >= 0 && rightThreadIdx >= 0) {
                    diffOneThread(
                        cloner = cloner,
                        output = output,
                        outputThreadId = outputThreadId,
                        name = name,
                        left = left,
                        leftRoot = leftRoots[leftThreadIdx],
                        right = right,
                        rightRoot = rightRoots[rightThreadIdx],
                    )
                } else if (leftThreadIdx >= 0) {
                    // Only in left -> whole thread is removed
                    output.context.setThreadName(outputThreadId, "$name: Thread not present in right trace")
                    copyTracepointSubtree(
                        output = output,
                        cloner = { cloner.cloneLeftTracePoint(it, -1) },
                        reader = left,
                        point = leftRoots[leftThreadIdx],
                        diffStatus = DiffStatus.REMOVED
                    )
                } else if (rightThreadIdx >= 0) {
                    // Only in right -> whole thread was added
                    output.context.setThreadName(outputThreadId, "$name: Thread not present in left trace")
                    copyTracepointSubtree(
                        output = output,
                        cloner = { cloner.cloneRightTracePoint(it, -1) },
                        reader = right,
                        point = rightRoots[rightThreadIdx],
                        diffStatus = DiffStatus.ADDED
                    )
                }
                output.writeThreadName(outputThreadId, output.context.getThreadName(outputThreadId))
                output.endRoot()
            }
        }
    }
    idMapStream.close()

    val diffEndTime = System.currentTimeMillis()
    val metaInfo = TraceMetaInfo.createDiff(left.metaInfo, right.metaInfo, diffStartTime, diffEndTime)

    packDiff(outputBaseName, idMapFile.absolutePath, threadMapFile.absolutePath, metaInfo)
}

private fun diffOneThread(
    cloner: TracePointCloner,
    output: DirectTraceWriter,
    outputThreadId: Int,
    name: String,
    left: LazyTraceReader,
    leftRoot: TRTracePoint,
    right: LazyTraceReader,
    rightRoot: TRTracePoint,
) {
    output.context.setThreadName(outputThreadId, name)
    // Diff from roots into virtual root for diff, if we need it
    val outputRoot = if (!TracePointComparator.strictEqual(leftRoot, rightRoot)) {
        TRMethodCallTracePoint(
            context = output.context,
            threadId = outputThreadId,
            codeLocationId = UNKNOWN_CODE_LOCATION_ID,
            methodId = output.context.createAndRegisterMethodDescriptor(
                "<diff>", "<root>", Types.MethodType(Types.VOID_TYPE)
            ).id,
            obj = null,
            parameters = emptyList(),
            eventId = cloner.generateEventId(),
        ).also { it.save(output) }
    } else {
        null
    }
    // Make diff!
    diffTracepointSubtree(
        output = output,
        cloner = cloner,
        cmp = TracePointComparator,
        outputRoot = outputRoot,
        leftReader = left,
        leftPoints = listOf(leftRoot),
        rightReader = right,
        rightPoints = listOf(rightRoot)
    )
    outputRoot?.saveFooter(output)
}

private fun matchThreads(
    left: LazyTraceReader,
    leftRoots: List<TRTracePoint>,
    right: LazyTraceReader,
    rightRoots: List<TRTracePoint>,
    options: TraceDiffOptions,
): List<ThreadMapElement> {
    val threadMap =
        when (options.threadsMatchingStrategy) {
            TraceDiffOptions.ThreadMatchingStrategy.AUTO_BY_NAME -> matchThreadsByName(left, leftRoots, right, rightRoots, options.forceMatchStartThreads)
            TraceDiffOptions.ThreadMatchingStrategy.AUTO_BY_INDEX -> matchThreadsByIdx(left, leftRoots, right, rightRoots, options.forceMatchStartThreads)
            TraceDiffOptions.ThreadMatchingStrategy.CUSTOM_BY_NAME -> matchThreadsByCustomName(left, leftRoots, right, rightRoots, options.customThreadNameMap!!)
            TraceDiffOptions.ThreadMatchingStrategy.CUSTOM_BY_INDEX -> matchThreadsByCustomIdx(left, leftRoots, right, rightRoots, options.customThreadIdxMap!!)
        }

    return threadMap
}

private fun matchStartThreads(
    left: LazyTraceReader,
    leftRoots: List<TRTracePoint>,
    right: LazyTraceReader,
    rightRoots: List<TRTracePoint>,
): ThreadMapElement? {

    val leftIdx = leftRoots.indexOfFirst { it.eventId == 0 }
    val rightIdx = rightRoots.indexOfFirst { it.eventId == 0 }

    if (leftIdx < 0 || rightIdx < 0) {
        return null
    }

    val leftName = left.context.getThreadName(leftIdx)
    val rightName = right.context.getThreadName(rightIdx)
    val name = "Start thread (left: $leftName, right: $rightName)"
    return ThreadMapElement(name, true, leftIdx, rightIdx)
}

private fun matchThreadsByName(
    left: LazyTraceReader,
    leftRoots: List<TRTracePoint>,
    right: LazyTraceReader,
    rightRoots: List<TRTracePoint>,
    forceMatchStartThreads: Boolean
): List<ThreadMapElement> {
    val threadMap = mutableListOf<ThreadMapElement>()

    val startMatch = if (forceMatchStartThreads) {
        val match = matchStartThreads(left, leftRoots, right, rightRoots)
        if (match != null) {
            threadMap.add(match)
        }
        match
    } else {
        null
    }

    val usedRightIdxes = mutableSetOf<Int>()

    leftRoots.forEachIndexed  { leftIdx, lr ->
        // Skip matched
        if (startMatch?.leftIdx == leftIdx) return@forEachIndexed

        val leftId = lr.threadId
        val leftName = left.context.getThreadName(leftId)
        // What to do with multiple threads with same name?
        val rightId = right.context.getThreadId(leftName)
        val rightIdx = rightRoots.indexOfFirst { it.threadId == rightId }
        // rightIdx can be -1, it is Ok
        usedRightIdxes.add(rightIdx)
        threadMap.add(ThreadMapElement(leftName, false, leftIdx, rightIdx))
    }

    rightRoots.forEachIndexed { rightIdx, rr ->
        // Skip matched
        if (startMatch?.rightIdx == rightIdx || usedRightIdxes.contains(rightIdx)) return@forEachIndexed

        val rightId = rr.threadId
        val rightName = right.context.getThreadName(rightId)

        // leftIdx will be -1, as no match for this name
        threadMap.add(ThreadMapElement(rightName, false, -1, rightIdx))
    }
    return threadMap
}

private fun matchThreadsByIdx(
    left: LazyTraceReader,
    leftRoots: List<TRTracePoint>,
    right: LazyTraceReader,
    rightRoots: List<TRTracePoint>,
    forceMatchStartThreads: Boolean
): List<ThreadMapElement> {
    val threadMap = mutableListOf<ThreadMapElement>()

    val startMatch = if (forceMatchStartThreads) {
        val match = matchStartThreads(left, leftRoots, right, rightRoots)
        if (match != null) {
            threadMap.add(match)
        }
        match
    } else {
        null
    }

    var leftIdx = 0
    var rightIdx = 0

    while (true) {
        if (leftIdx == startMatch?.leftIdx) {
            // Skip matched one
            leftIdx++
        }
        if (rightIdx == startMatch?.rightIdx) {
            // Skip matched one
            rightIdx++
        }
        val match = if (leftIdx < leftRoots.size && rightIdx < rightRoots.size) {
            val lr = leftRoots[leftIdx]
            val leftName = left.context.getThreadName(lr.threadId)

            val rr = rightRoots[rightIdx]
            val rightName = right.context.getThreadName(rr.threadId)

            val name = if (leftName == rightName) {
                leftName
            } else {
                "left: $leftName, right: $rightName"

            }
            ThreadMapElement(name, false, leftIdx, rightIdx)
        } else if (leftIdx < leftRoots.size) {
            val lr = leftRoots[leftIdx]
            val leftName = left.context.getThreadName(lr.threadId)
            ThreadMapElement(leftName, false, leftIdx, -1)
        } else if (rightIdx < rightRoots.size) {
            val rr = rightRoots[rightIdx]
            val rightName = right.context.getThreadName(rr.threadId)
            ThreadMapElement(rightName, false, -1, rightIdx)
        } else {
            // Both arrays ends
            break
        }
        threadMap.add(match)

        leftIdx++
        rightIdx++
    }

    return threadMap
}

private fun matchThreadsByCustomName(
    left: LazyTraceReader,
    leftRoots: List<TRTracePoint>,
    right: LazyTraceReader,
    rightRoots: List<TRTracePoint>,
    customThreadNameMap: Map<String, String>
): List<ThreadMapElement> {
    val threadMap = mutableListOf<ThreadMapElement>()
    val usedLeftIdx = mutableSetOf<Int>()
    val usedRightIdx = mutableSetOf<Int>()

    customThreadNameMap.forEach { (leftName, rightName) ->
        val leftId = left.context.getThreadId(leftName)
        require(leftId >= 0) { "There is no thread $leftName in left trace" }
        val leftIdx = leftRoots.indexOfFirst { it.threadId == leftId }
        require(leftIdx >= 0) { "There is no trace points in thread $leftName in left trace" }
        usedLeftIdx.add(leftIdx)

        val rightId = right.context.getThreadId(rightName)
        require(rightId >= 0) { "There is no thread $rightName in right trace" }
        val rightIdx = rightRoots.indexOfFirst { it.threadId == rightId }
        require(rightIdx >= 0) { "There is no trace points in thread $rightName in right trace" }
        require(usedRightIdx.add(rightIdx)) { "Thread $rightName from right trace was mapped twice" }

        val name = if (leftName == rightName) {
            leftName
        } else {
            "left: $leftName, right: $rightName"
        }

        threadMap.add(ThreadMapElement(name, false, leftIdx, rightIdx))
    }

    // Map unmapped to -1
    leftRoots.forEachIndexed { idx, root ->
        if (usedLeftIdx.contains(idx)) return@forEachIndexed
        threadMap.add(ThreadMapElement(left.context.getThreadName(root.threadId), false, idx, -1))
    }

    // Map unmapped to -1
    rightRoots.forEachIndexed { idx, root ->
        if (usedRightIdx.contains(idx)) return@forEachIndexed
        threadMap.add(ThreadMapElement(right.context.getThreadName(root.threadId), false, -1, idx))
    }

    return threadMap
}

private fun matchThreadsByCustomIdx(
    left: LazyTraceReader,
    leftRoots: List<TRTracePoint>,
    right: LazyTraceReader,
    rightRoots: List<TRTracePoint>,
    customThreadIdxMap: Map<Int, Int>
): List<ThreadMapElement> {
    val threadMap = mutableListOf<ThreadMapElement>()
    val usedLeftIdx = mutableSetOf<Int>()
    val usedRightIdx = mutableSetOf<Int>()

    customThreadIdxMap.forEach { (leftIdx, rightIdx) ->
        require(leftIdx in 0 ..< leftRoots.size) { "Thread index $leftIdx is not found in left trace" }
        val leftId = leftRoots[leftIdx].threadId
        val leftName = left.context.getThreadName(leftId)
        usedLeftIdx.add(leftIdx)

        require(rightIdx in 0 ..< rightRoots.size) { "Thread index $rightIdx is not found in right trace" }
        val rightId = rightRoots[rightIdx].threadId
        val rightName = right.context.getThreadName(rightId)
        require(usedRightIdx.add(rightIdx)) { "Thread $rightName from right trace was mapped twice" }

        val name = if (leftName == rightName) {
            leftName
        } else {
            "left: $leftName, right: $rightName"
        }

        threadMap.add(ThreadMapElement(name, false, leftIdx, rightIdx))
    }

    // Map unmapped to -1
    leftRoots.forEachIndexed { idx, root ->
        if (usedLeftIdx.contains(idx)) return@forEachIndexed
        threadMap.add(ThreadMapElement(left.context.getThreadName(root.threadId), false, idx, -1))
    }

    // Map unmapped to -1
    rightRoots.forEachIndexed { idx, root ->
        if (usedRightIdx.contains(idx)) return@forEachIndexed
        threadMap.add(ThreadMapElement(right.context.getThreadName(root.threadId), false, -1, idx))
    }

    return threadMap
}

private fun saveThreadMap(threadMap:List<ThreadMapElement>): File {
    val threadMapFile = File.createTempFile("trace-diff-", ".$THREAD_MAP_FILENAME_EXT")
        .also { it.deleteOnExit() }
    val threadMapStream = FileOutputStream(threadMapFile).buffered(OUTPUT_BUFFER_SIZE)
    threadMapStream.use {
        val out = DataOutputStream(it)
        out.writeInt(threadMap.size)
        threadMap.forEachIndexed { index, element ->
            out.writeInt(index)
            out.writeInt(element.leftIdx)
            out.writeInt(element.rightIdx)
        }
    }
    return threadMapFile
}

private fun copyTracepointSubtree(
    output: TraceWriter,
    cloner: (TRTracePoint) -> TRTracePoint,
    reader: LazyTraceReader,
    point: TRTracePoint,
    diffStatus: DiffStatus,
    outputParent: TRContainerTracePoint? = null
) {
    val outputPoint = cloner(point)
    outputPoint.diffStatus = diffStatus
    outputParent?.addChild(outputPoint)
    outputPoint.save(output)
    // Save all children recursively, if needed
    if (outputPoint is TRContainerTracePoint && point is TRContainerTracePoint) {
        // TODO: Batching
        reader.loadAllChildren(point)
        point.events.forEach { p ->
            if (p == null) return@forEach
            copyTracepointSubtree(output, cloner, reader, p, diffStatus, outputPoint)
        }
        outputPoint.saveFooter(output)
        // Free memory
        point.unloadAllChildren()
        outputPoint.unloadAllChildren()
    }
}

private fun diffTracepointSubtree(
    output: TraceWriter,
    cloner: TracePointCloner,
    cmp: TracePointComparator,
    outputRoot: TRContainerTracePoint?,
    leftReader: LazyTraceReader,
    leftPoints: List<TRTracePoint?>,
    rightReader: LazyTraceReader,
    rightPoints: List<TRTracePoint?>
) {
    val diff = diffTracePointLists(cmp, leftPoints, rightPoints)
    diff.forEach { line ->
        when (line) {
            is UnchangedDiffLine -> {
                // We know, it cannot be null
                val lp = leftPoints[line.leftIdx]!!
                val rp = rightPoints[line.rightIdx]!!
                // It is editing-equivalent trace points
                val strict = cmp.strictEqual(lp, rp)

                if (!strict) {
                    // "Remove" left and make it without children
                    val oldPoint = cloner.cloneLeftTracePoint(lp, rp.eventId)
                    oldPoint.diffStatus = DiffStatus.EDITED_OLD
                    outputRoot?.addChild(oldPoint)
                    oldPoint.save(output)
                    if (oldPoint is TRContainerTracePoint) {
                        oldPoint.saveFooter(output)
                    }
                }
                // Copy tracepoint itself from right subtree for now
                val outputPoint = cloner.cloneRightTracePoint(rp, lp.eventId)
                outputPoint.diffStatus = if (strict) DiffStatus.UNCHANGED else DiffStatus.EDITED_NEW
                outputRoot?.addChild(outputPoint)
                outputPoint.save(output)

                // Maybe, we need to go deeper?
                if (outputPoint is TRContainerTracePoint) {
                    val lc = lp as TRContainerTracePoint
                    leftReader.loadAllChildren(lc)
                    val rc = rp as TRContainerTracePoint
                    rightReader.loadAllChildren(rc)

                    diffTracepointSubtree(
                        output = output,
                        cloner = cloner,
                        cmp = TracePointComparator,
                        outputRoot = outputPoint,
                        leftReader = leftReader,
                        leftPoints = lc.events,
                        rightReader = rightReader,
                        rightPoints = rc.events
                    )

                    outputPoint.saveFooter(output)
                    outputPoint.unloadAllChildren()
                    lc.unloadAllChildren()
                    rc.unloadAllChildren()
                }
            }
            is RemovedDiffLine -> {
                copyTracepointSubtree(
                    output = output,
                    cloner = { cloner.cloneLeftTracePoint(it, -1) },
                    reader = leftReader,
                    point = leftPoints[line.leftIdx]!!,
                    diffStatus = DiffStatus.REMOVED,
                    outputParent = outputRoot
                )
            }
            is AddedDiffLine -> {
                copyTracepointSubtree(
                    output = output,
                    cloner = { cloner.cloneRightTracePoint(it, -1) },
                    reader = rightReader,
                    point = rightPoints[line.rightIdx]!!,
                    diffStatus = DiffStatus.ADDED,
                    outputParent = outputRoot
                )
            }
        }
    }
}

private fun <T> calculateNotNullSize(list: List<T?>): Int {
    if (list.isEmpty()) return 0
    val firstNull = list.indexOf(null)
    return if (firstNull == -1)
        list.size
    else
        firstNull
}

private fun diffTracePointLists(cmp: TracePointComparator, leftPoints: List<TRTracePoint?>, rightPoints: List<TRTracePoint?>): List<DiffLine> =
    diffLists(leftPoints, calculateNotNullSize(leftPoints), rightPoints, calculateNotNullSize(rightPoints)) { l, r -> cmp.editIndependentEqual(l!!,r!!) }
