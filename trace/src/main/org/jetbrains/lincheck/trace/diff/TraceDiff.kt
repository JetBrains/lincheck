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
import org.jetbrains.lincheck.trace.DiffStatus
import org.jetbrains.lincheck.trace.DirectTraceWriter
import org.jetbrains.lincheck.trace.LazyTraceReader
import org.jetbrains.lincheck.trace.TRContainerTracePoint
import org.jetbrains.lincheck.trace.TRMethodCallTracePoint
import org.jetbrains.lincheck.trace.TRTracePoint
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.TraceWriter
import org.jetbrains.lincheck.trace.UNKNOWN_CODE_LOCATION_ID
import org.jetbrains.lincheck.trace.openNewStandardDataAndIndex

fun diffTwoTraces(left: LazyTraceReader, right: LazyTraceReader, outputBaseName: String) {
    // Prepare writer to save result
    val outputContext = TraceContext()
    val (outputData, outputIndex) = openNewStandardDataAndIndex(outputBaseName)
    val output = DirectTraceWriter(outputData, outputIndex, outputContext)

    // Load all left and right roots.
    val leftRoots = left.readRoots()
    val rightRoots = right.readRoots()

    // Match threads by name for now
    // Try to match same names
    val threadMap = mutableMapOf<String, Triple<Int, Int, Int>>()
    var diffThreadId = 0
    left.context.threadNames
        .forEach { tn ->
            threadMap[tn] = Triple(left.context.getThreadId(tn), right.context.getThreadId(tn), diffThreadId++)
        }
    right.context.threadNames
        .filter { !threadMap.contains(it) }
        .forEach { tn ->
            threadMap[tn] = Triple(left.context.getThreadId(tn), right.context.getThreadId(tn), diffThreadId++)
        }

    var globalOutputEventId = 0
    output.use {
        // Ok, we have all threads matched, work on them one by one.
        threadMap.forEach { name, (leftThreadId, rightThreadId, outputThreadId) ->
            // Make cloner for this target thread
            val cloner = TracePointCloner(output.context, outputThreadId, globalOutputEventId)
            output.startNewRoot(outputThreadId)
            if (leftThreadId >= 0 && rightThreadId >= 0) {
                output.context.setThreadName(outputThreadId, name)
                // Diff from roots into virtual root for diff, as it can have two children: added and removed
                val outputRoot = TRMethodCallTracePoint(
                    context = output.context,
                    threadId = outputThreadId,
                    codeLocationId = UNKNOWN_CODE_LOCATION_ID,
                    methodId = output.context.getOrCreateMethodId(
                        "<diff>",
                        "<diff>",
                        Types.MethodType(Types.VOID_TYPE)
                    ),
                    obj = null,
                    parameters = emptyList()
                )
                outputRoot.save(output)
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
                outputRoot.saveFooter(output)
            } else if (leftThreadId >= 0) {
                // Only in left -> whole thread is removed
                output.context.setThreadName(outputThreadId, "$name: Thread not present in right trace")
                copyTracepointSubtree(
                    output = output,
                    cloner = cloner,
                    reader = left,
                    point = leftRoots[leftThreadId],
                    diffStatus = DiffStatus.REMOVED
                )
            } else if (rightThreadId >= 0) {
                // Only in right -> whole thread was added
                output.context.setThreadName(outputThreadId, "$name: Thread not present in left trace")
                copyTracepointSubtree(
                    output = output,
                    cloner = cloner,
                    reader = right,
                    point = rightRoots[rightThreadId],
                    diffStatus = DiffStatus.ADDED
                )
            }
            output.writeThreadName(outputThreadId, output.context.getThreadName(outputThreadId))
            output.endRoot()
            // Start from this in next thread
            globalOutputEventId = cloner.eventId
        }
    }
}

private fun copyTracepointSubtree(
    output: TraceWriter,
    cloner: TracePointCloner,
    reader: LazyTraceReader,
    point: TRTracePoint,
    diffStatus: DiffStatus,
    outputParent: TRContainerTracePoint? = null
) {
    val outputPoint =cloner.cloneTracePoint(point)
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
    outputRoot: TRContainerTracePoint,
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
                val rightStatus = if (cmp.strictEqual(lp, rp)) DiffStatus.UNCHANGED else DiffStatus.ADDED

                if (rightStatus == DiffStatus.ADDED) {
                    // "Remove" left and make it without children, add right
                    val removedPoint = cloner.cloneTracePoint(lp)
                    removedPoint.diffStatus = DiffStatus.REMOVED
                    outputRoot.addChild(removedPoint)
                    removedPoint.save(output)
                    if (removedPoint is TRContainerTracePoint) {
                        removedPoint.saveFooter(output)
                    }
                }
                // Copy tracepoint itself from right subtree for now
                val outputPoint = cloner.cloneTracePoint(rp)
                outputPoint.diffStatus = rightStatus
                outputRoot.addChild(outputPoint)
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
                    cloner = cloner,
                    reader = leftReader,
                    point = leftPoints[line.leftIdx]!!,
                    diffStatus = DiffStatus.REMOVED,
                    outputParent = outputRoot
                )
            }
            is AddedDiffLine -> {
                copyTracepointSubtree(
                    output = output,
                    cloner = cloner,
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
