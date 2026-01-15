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

private typealias ThreadMap = Map<String, Triple<Int, Int, Int>>


fun diffTwoTraces(left: LazyTraceReader, right: LazyTraceReader, outputBaseName: String) {
    require(!left.isDiff) { "Cannot diff other diffs: left trace is diff" }
    require(!right.isDiff) { "Cannot diff other diffs: right trace is diff" }

    val metaInfo = TraceMetaInfo.startDiff(left.metaInfo, right.metaInfo)

    // Load all left and right roots.
    val leftRoots = left.readRoots()
    val rightRoots = right.readRoots()

    // Match threads by name for now
    // Try to match same names
    val threadMap = correlateThreadsByName(left, right)
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
        // Ok, we have all threads matched, work on them one by one.
        threadMap.forEach { name, (leftThreadId, rightThreadId, outputThreadId) ->
            cloner.setThread(outputThreadId)

            // Make cloner for this target thread
            output.startNewRoot(outputThreadId)
            if (leftThreadId >= 0 && rightThreadId >= 0) {
                output.context.setThreadName(outputThreadId, name)
                // Diff from roots into virtual root for diff, if we need it
                val outputRoot = if (!TracePointComparator.strictEqual(leftRoots[leftThreadId], rightRoots[rightThreadId])) {
                    TRMethodCallTracePoint(
                        context = output.context,
                        threadId = outputThreadId,
                        codeLocationId = UNKNOWN_CODE_LOCATION_ID,
                        methodId = output.context.getOrCreateMethodId(
                            "<diff>",
                            "<root>",
                            Types.MethodType(Types.VOID_TYPE)
                        ),
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
                    leftPoints = listOf(leftRoots[leftThreadId]),
                    rightReader = right,
                    rightPoints = listOf(rightRoots[rightThreadId])
                )
                outputRoot?.saveFooter(output)
            } else if (leftThreadId >= 0) {
                // Only in left -> whole thread is removed
                output.context.setThreadName(outputThreadId, "$name: Thread not present in right trace")
                copyTracepointSubtree(
                    output = output,
                    cloner = { cloner.cloneLeftTracePoint(it,-1) },
                    reader = left,
                    point = leftRoots[leftThreadId],
                    diffStatus = DiffStatus.REMOVED
                )
            } else if (rightThreadId >= 0) {
                // Only in right -> whole thread was added
                output.context.setThreadName(outputThreadId, "$name: Thread not present in left trace")
                copyTracepointSubtree(
                    output = output,
                    cloner = { cloner.cloneRightTracePoint(it,-1) },
                    reader = right,
                    point = rightRoots[rightThreadId],
                    diffStatus = DiffStatus.ADDED
                )
            }
            output.writeThreadName(outputThreadId, output.context.getThreadName(outputThreadId))
            output.endRoot()
        }
    }
    idMapStream.close()

    metaInfo.traceEnded()
    packDiff(outputBaseName, idMapFile.absolutePath, threadMapFile.absolutePath, metaInfo)
}

private fun correlateThreadsByName(
    left: LazyTraceReader,
    right: LazyTraceReader
): ThreadMap {
    val threadMap = mutableMapOf<String, Triple<Int, Int, Int>>()
    var diffThreadId = 0
    left.context.threadNames()
        .forEach { tn ->
            threadMap[tn] = Triple(left.context.getThreadId(tn), right.context.getThreadId(tn), diffThreadId++)
        }
    right.context.threadNames()
        .filter { !threadMap.contains(it) }
        .forEach { tn ->
            threadMap[tn] = Triple(left.context.getThreadId(tn), right.context.getThreadId(tn), diffThreadId++)
        }
    return threadMap
}

private fun saveThreadMap(threadMap: ThreadMap): File {
    val threadMapFile = File.createTempFile("trace-diff-", ".$THREAD_MAP_FILENAME_EXT")
        .also { it.deleteOnExit() }
    val threadMapStream = FileOutputStream(threadMapFile).buffered(OUTPUT_BUFFER_SIZE)
    threadMapStream.use {
        val out = DataOutputStream(it)
        out.writeInt(threadMap.size)
        threadMap.forEach { (_, triple) ->
            out.writeInt(triple.third)
            out.writeInt(triple.first)
            out.writeInt(triple.second)
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
            copyTracepointSubtree(output, cloner, reader, p!!, diffStatus, outputPoint)
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

private fun diffTracePointLists(cmp: TracePointComparator, leftPoints: List<TRTracePoint?>, rightPoints: List<TRTracePoint?>): List<DiffLine> =
    diffLists(leftPoints, rightPoints) { l, r -> cmp.editIndependentEqual(l!!,r!!) }
