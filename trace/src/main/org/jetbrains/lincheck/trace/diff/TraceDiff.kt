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
import org.jetbrains.lincheck.trace.serialization.*
import org.jetbrains.lincheck.trace.tree.compressedView
import org.jetbrains.lincheck.trace.tree.readTraceTrees
import org.jetbrains.lincheck.util.tree.Tree
import org.jetbrains.lincheck.util.tree.unloadChildren
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.collections.component1
import kotlin.collections.component2

internal data class ThreadMapElement(val name: String, val isStartThread: Boolean, val leftIdx: Int, val rightIdx: Int)

fun diffTwoTraces(left: LazyTraceReader, right: LazyTraceReader, outputBaseName: String) =
    diffTwoTraces(left, right, outputBaseName, TraceDiffOptions.DEFAULT)

fun diffTwoTraces(left: LazyTraceReader, right: LazyTraceReader, outputBaseName: String, options: TraceDiffOptions) {
    if (!options.forceDiff) {
        require(!left.isDiff) { "Cannot diff other diffs: left trace is diff" }
        require(!right.isDiff) { "Cannot diff other diffs: right trace is diff" }
    }
    
    val diffStartTime = System.currentTimeMillis()

    // Load all left and right roots as lazy trees (with the compressing view applied, as the readers did before).
    val leftRoots = left.readCompressedTraceRoots()
    val rightRoots = right.readCompressedTraceRoots()

    // Match threads by name for now
    // Try to match same names
    val threadMap = matchThreads(left, leftRoots.map { it.data }, right, rightRoots.map { it.data }, options)

    // Check map, if needed
    if (options.unmatchedThreadsBehavior == TraceDiffOptions.UnmatchedThreadsBehavior.ERROR) {
        threadMap.forEach {
            if (it.rightIdx < 0) {
                val lid = leftRoots[it.leftIdx].data.threadId
                throw IllegalStateException("Thread #${it.leftIdx} (id=$lid, name=${it.name}) from left trace was not matched with any thread in right trace.")
            }
            if (it.leftIdx < 0) {
                val rid = rightRoots[it.rightIdx].data.threadId
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

    val idMapFile = File.createTempFile("trace-diff-", ".${ID_MAP_FILENAME_EXT}")
        .also { it.deleteOnExit() }
    val idMapStream = DataOutputStream(FileOutputStream(idMapFile).buffered(OUTPUT_BUFFER_SIZE))

    val cloner = TracePointCloner(output.context, idMapStream)
    var points = 0
    output.use {
        if (options.diffOnlyStartThreads) {
            // Diff only thread with eventIds = 0
            val threadMapping = threadMap.getOrNull(0)
            if (threadMapping == null || !threadMapping.isStartThread) {
                throw IllegalStateException("Only start thread diff is requested, but no start threads pair was found.")
            }
            cloner.setThread(0)
            output.startNewRoot(0)
            points += diffOneThread(
                cloner = cloner,
                output = output,
                outputThreadId = 0,
                name = threadMapping.name,
                leftRoot = leftRoots[threadMapping.leftIdx],
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
                    output.context.setThreadName(outputThreadId, name)
                    points += diffOneThread(
                        cloner = cloner,
                        output = output,
                        outputThreadId = outputThreadId,
                        name = name,
                        leftRoot = leftRoots[leftThreadIdx],
                        rightRoot = rightRoots[rightThreadIdx],
                    )
                } else if (leftThreadIdx >= 0) {
                    // Only in left -> whole thread is removed
                    output.context.setThreadName(outputThreadId, "$name: Thread not present in right trace")
                    points += copyTracepointSubtree(
                        output = output,
                        cloner = { cloner.cloneLeftTracePoint(it, -1) },
                        node = leftRoots[leftThreadIdx],
                        diffStatus = DiffStatus.REMOVED
                    )
                } else if (rightThreadIdx >= 0) {
                    // Only in right -> whole thread was added
                    output.context.setThreadName(outputThreadId, "$name: Thread not present in left trace")
                    points += copyTracepointSubtree(
                        output = output,
                        cloner = { cloner.cloneRightTracePoint(it, -1) },
                        node = rightRoots[rightThreadIdx],
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
    val metaInfo = TraceMetaInfo.createDiff(
        leftMetaInfo = left.metaInfo,
        rightMetaInfo = right.metaInfo,
        startTime = diffStartTime,
        endTime = diffEndTime,
        points = points
    )

    packDiff(outputBaseName, idMapFile.absolutePath, threadMapFile.absolutePath, metaInfo)
}

private fun LazyTraceReader.readCompressedTraceRoots(): List<Tree.Node<TRTracePoint>> =
    readTraceTrees().map { checkNotNull(it.compressedView(context).root) }

private fun diffOneThread(
    cloner: TracePointCloner,
    output: DirectTraceWriter,
    outputThreadId: Int,
    name: String,
    leftRoot: Tree.Node<TRTracePoint>,
    rightRoot: Tree.Node<TRTracePoint>,
): Int {
    output.context.setThreadName(outputThreadId, name)
    var points = 0
    // Diff from roots into virtual root for diff, if we need it
    val outputRoot = if (!TracePointComparator.strictEqual(leftRoot.data, rightRoot.data)) {
        points = 1
        TRMethodCallTracePoint(
            context = output.context,
            threadId = outputThreadId,
            codeLocationId = UNKNOWN_CODE_LOCATION_ID,
            methodId = output.context.createAndRegisterMethodDescriptor(
                "<diff>", "<root>", Types.MethodType(Types.VOID_TYPE)
            ).id,
            obj = TRNull,
            parameters = emptyList(),
            eventId = cloner.generateEventId(),
        ).also { output.writeTRMethodCallTracePoint(it) }
    } else {
        null
    }
    // Make diff!
    points += diffTracepointSubtree(
        output = output,
        cloner = cloner,
        cmp = TracePointComparator,
        outputRoot = outputRoot,
        leftNodes = listOf(leftRoot),
        rightNodes = listOf(rightRoot)
    )
    outputRoot?.let { output.writeTRMethodCallTracePointFooter(it) }
    return points
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
    val startMatch = tryMatchStartThreads(forceMatchStartThreads, left, leftRoots, right, rightRoots, threadMap)

    val usedRightIdxes = mutableSetOf<Int>()

    leftRoots.forEachIndexed  { leftIdx, lr ->
        // Skip matched
        if (startMatch?.leftIdx == leftIdx) return@forEachIndexed

        val leftId = lr.threadId
        val leftName = left.context.getThreadName(leftId)
        val rightIdx = findFirstUnusedThreadByName(right, leftName, rightRoots, usedRightIdxes)
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
    val startMatch = tryMatchStartThreads(forceMatchStartThreads, left, leftRoots, right, rightRoots, threadMap)

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

private fun tryMatchStartThreads(
    forceMatchStartThreads: Boolean,
    left: LazyTraceReader,
    leftRoots: List<TRTracePoint>,
    right: LazyTraceReader,
    rightRoots: List<TRTracePoint>,
    threadMap: MutableList<ThreadMapElement>
): ThreadMapElement? {
    val startMatch = if (forceMatchStartThreads) {
        val match = matchStartThreads(left, leftRoots, right, rightRoots)
        if (match != null) {
            threadMap.add(match)
        }
        match
    } else {
        null
    }
    return startMatch
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
        val leftIdx = findFirstUnusedThreadByName(left, leftName, leftRoots, usedLeftIdx)
        require(leftIdx >= 0) { "There is no trace points in thread $leftName in left trace" }
        usedLeftIdx.add(leftIdx)

        val rightIdx = findFirstUnusedThreadByName(right, rightName, rightRoots, usedLeftIdx)
        require(rightIdx >= 0) { "There is no trace points in thread $rightName in right trace" }
        require(usedRightIdx.add(rightIdx)) { "Thread $rightName from right trace was mapped twice" }

        val name = makeThreadName(leftName, rightName)

        threadMap.add(ThreadMapElement(name, false, leftIdx, rightIdx))
    }

    mapUnmappedThreads(threadMap, left, leftRoots, usedLeftIdx, right, rightRoots, usedRightIdx)

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

        val name = makeThreadName(leftName, rightName)
        threadMap.add(ThreadMapElement(name, false, leftIdx, rightIdx))
    }

    mapUnmappedThreads(threadMap, left, leftRoots, usedLeftIdx, right, rightRoots, usedRightIdx)

    return threadMap
}

private fun findFirstUnusedThreadByName(
    reader: LazyTraceReader,
    name: String,
    roots: List<TRTracePoint>,
    usedIdx: MutableSet<Int>,
): Int {
    val ids = reader.context.getThreadIds(name)
    return roots.indices.firstOrNull { !usedIdx.contains(it) && ids.contains(roots[it].threadId) } ?: -1
}

private fun makeThreadName(leftName: String, rightName: String): String =
    if (leftName == rightName) {
        leftName
    } else {
        "left: $leftName, right: $rightName"
    }

private fun mapUnmappedThreads(
    threadMap: MutableList<ThreadMapElement>,
    left: LazyTraceReader,
    leftRoots: List<TRTracePoint>,
    usedLeftIdx: MutableSet<Int>,
    right: LazyTraceReader,
    rightRoots: List<TRTracePoint>,
    usedRightIdx: MutableSet<Int>
) {
    leftRoots.forEachIndexed { idx, root ->
        if (usedLeftIdx.contains(idx)) return@forEachIndexed
        threadMap.add(ThreadMapElement(left.context.getThreadName(root.threadId), false, idx, -1))
    }

    // Map unmapped to -1
    rightRoots.forEachIndexed { idx, root ->
        if (usedRightIdx.contains(idx)) return@forEachIndexed
        threadMap.add(ThreadMapElement(right.context.getThreadName(root.threadId), false, -1, idx))
    }
}

private fun saveThreadMap(threadMap: List<ThreadMapElement>): File {
    val threadMapFile = File.createTempFile("trace-diff-", ".${THREAD_MAP_FILENAME_EXT}")
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
    node: Tree.Node<TRTracePoint>,
    diffStatus: DiffStatus,
    outputParent: TRContainerTracePoint? = null
): Int {
    var points = 1
    val outputPoint = cloner(node.data)
    outputPoint.diffStatus = diffStatus
    countCopiedChild(outputParent)
    output.writeTracePoint(outputPoint)
    // Save all children recursively, if needed
    if (outputPoint is TRContainerTracePoint) {
        node.children.forEach { child ->
            points += copyTracepointSubtree(output, cloner, child, diffStatus, outputPoint)
        }
        output.writeTracePointFooter(outputPoint)
        // Free memory
        node.unloadChildren()
    }
    return points
}

// The output tree is not materialized: nesting is expressed by the save/saveFooter call order.
// The only parent-side bookkeeping left is the loop-iteration count, written in the loop's footer.
private fun countCopiedChild(outputParent: TRContainerTracePoint?) {
    if (outputParent is TRLoopTracePoint) {
        outputParent.incrementIterations()
    }
}

private fun diffTracepointSubtree(
    output: TraceWriter,
    cloner: TracePointCloner,
    cmp: TracePointComparator,
    outputRoot: TRContainerTracePoint?,
    leftNodes: List<Tree.Node<TRTracePoint>>,
    rightNodes: List<Tree.Node<TRTracePoint>>
): Int {
    var points = 0
    val diff = diffLists(left = leftNodes, right = rightNodes) { l, r -> cmp.editIndependentEqual(l.data, r.data) }
    diff.forEach { line ->
        when (line) {
            is UnchangedDiffLine -> {
                val ln = leftNodes[line.leftIdx]
                val rn = rightNodes[line.rightIdx]
                val lp = ln.data
                val rp = rn.data
                // It is editing-equivalent trace points
                val strict = cmp.strictEqual(lp, rp)

                if (!strict) {
                    // "Remove" left and make it without children
                    val oldPoint = cloner.cloneLeftTracePoint(lp, rp.eventId)
                    oldPoint.diffStatus = DiffStatus.EDITED_OLD
                    countCopiedChild(outputRoot)
                    output.writeTracePoint(oldPoint)
                    if (oldPoint is TRContainerTracePoint) {
                        output.writeTracePointFooter(oldPoint)
                    }
                    points += 1
                }
                // Copy tracepoint itself from right subtree for now
                val outputPoint = cloner.cloneRightTracePoint(rp, lp.eventId)
                outputPoint.diffStatus = if (strict) DiffStatus.UNCHANGED else DiffStatus.EDITED_NEW
                countCopiedChild(outputRoot)
                output.writeTracePoint(outputPoint)
                points += 1

                // Maybe, we need to go deeper?
                if (outputPoint is TRContainerTracePoint) {
                    points += diffTracepointSubtree(
                        output = output,
                        cloner = cloner,
                        cmp = TracePointComparator,
                        outputRoot = outputPoint,
                        leftNodes = ln.children,
                        rightNodes = rn.children
                    )

                    output.writeTracePointFooter(outputPoint)
                    // Free memory
                    ln.unloadChildren()
                    rn.unloadChildren()
                }
            }
            is RemovedDiffLine -> {
                points += copyTracepointSubtree(
                    output = output,
                    cloner = { cloner.cloneLeftTracePoint(it, -1) },
                    node = leftNodes[line.leftIdx],
                    diffStatus = DiffStatus.REMOVED,
                    outputParent = outputRoot
                )
            }
            is AddedDiffLine -> {
                points += copyTracepointSubtree(
                    output = output,
                    cloner = { cloner.cloneRightTracePoint(it, -1) },
                    node = rightNodes[line.rightIdx],
                    diffStatus = DiffStatus.ADDED,
                    outputParent = outputRoot
                )
            }
        }
    }
    return points
}