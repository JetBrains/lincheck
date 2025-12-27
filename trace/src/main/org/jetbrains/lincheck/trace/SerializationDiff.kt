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

import org.jetbrains.lincheck.descriptors.Types

fun diffTwoTraces(left: LazyTraceReader, right: LazyTraceReader, outputBaseName: String) {
    // Prepare strategy to save result
    val outputContext = TraceContext()
    val streams = openNewStandardDataAndIndex(outputBaseName)
    val output = DirectTraceWriter(streams.first, streams.second, outputContext)

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

    output.use {
        // Ok, we have all threads matched, work on them one by one.
        threadMap.forEach { name, (leftThreadId, rightThreadId, outputThreadId) ->
            output.startNewRoot(outputThreadId)
            if (leftThreadId >= 0 && rightThreadId >= 0) {
                output.context.setThreadName(outputThreadId, name)
                // Diff from roots into virtual root for diff, as it can have two children: added and removed
                val outputRoot = TRMethodCallTracePoint(
                    context = output.context,
                    threadId = outputThreadId,
                    codeLocationId = UNKNOWN_CODE_LOCATION_ID,
                    methodId = output.context.getOrCreateMethodId("<diff>", "<diff>", Types.MethodType(Types.VOID_TYPE)),
                    obj = null,
                    parameters = emptyList()
                )
                outputRoot.save(output)
                // Make diff!
                diffTracepointSubtree(
                    output = output,
                    outputContext = output.context,
                    outputThreadId = outputThreadId,
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
                    outputContext = output.context,
                    outputThreadId = outputThreadId,
                    reader = left,
                    point = leftRoots[leftThreadId],
                    diffStatus = DiffStatus.REMOVED
                )
            } else if (rightThreadId >= 0) {
                // Only in right -> whole thread was added
                output.context.setThreadName(outputThreadId, "$name: Thread not present in left trace")
                copyTracepointSubtree(
                    output = output,
                    outputContext = output.context,
                    outputThreadId = outputThreadId,
                    reader = right,
                    point = rightRoots[rightThreadId],
                    diffStatus = DiffStatus.ADDED
                )
            }
            output.writeThreadName(outputThreadId, output.context.getThreadName(outputThreadId))
            output.endRoot()
        }
    }
}

private fun copyTracepointSubtree(
    output: TraceWriter,
    outputContext: TraceContext,
    outputThreadId: Int,
    reader: LazyTraceReader,
    point: TRTracePoint,
    diffStatus: DiffStatus,
    outputParent: TRContainerTracePoint? = null
) {
    val outputPoint = point.cloneToNewContext(outputContext, outputThreadId)
    outputPoint.diffStatus = diffStatus
    outputParent?.addChild(outputPoint)
    outputPoint.save(output)
    // Save all children recursively, if needed
    if (outputPoint is TRContainerTracePoint && point is TRContainerTracePoint) {
        // TODO: Batching
        reader.loadAllChildren(point)
        point.events.forEach { p ->
            copyTracepointSubtree(output, outputContext, outputThreadId, reader, p!!, diffStatus, outputPoint)
        }
        outputPoint.saveFooter(output)
        // Free memory
        point.unloadAllChildren()
        outputPoint.unloadAllChildren()
    }
}

private data class DiffLine(val status: DiffStatus, val leftIdx: Int, val rightIDx: Int)

private fun diffTracepointSubtree(
    output: TraceWriter,
    outputContext: TraceContext,
    outputThreadId: Int,
    outputRoot: TRContainerTracePoint,
    leftReader: LazyTraceReader,
    leftPoints: List<TRTracePoint?>,
    rightReader: LazyTraceReader,
    rightPoints: List<TRTracePoint?>
) {
    val diff = diffTracePointLists(leftPoints, rightPoints)
    diff.forEach { (status, leftIdx, rightIdx) ->
        when (status) {
            DiffStatus.UNCHANGED -> {
                // We know, it cannot be null
                val lp = leftPoints[leftIdx]!!
                val rp = rightPoints[rightIdx]!!
                // It is editing-equivalent trace points
                val realStatus = if (lp.strictHashForDiff == rp.strictHashForDiff) DiffStatus.UNCHANGED else DiffStatus.EDITED
                // Copy tracepoint itself from right subtree for now
                val outputPoint = rp.cloneToNewContext(outputContext, outputThreadId)
                outputPoint.diffStatus = realStatus
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
                        outputContext = outputContext,
                        outputThreadId = outputThreadId,
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
            DiffStatus.REMOVED -> {
                copyTracepointSubtree(
                    output = output,
                    outputContext = outputContext,
                    outputThreadId = outputThreadId,
                    reader = leftReader,
                    point = leftPoints[leftIdx]!!,
                    diffStatus = DiffStatus.REMOVED,
                    outputParent = outputRoot
                )
            }
            DiffStatus.ADDED -> {
                copyTracepointSubtree(
                    output = output,
                    outputContext = outputContext,
                    outputThreadId = outputThreadId,
                    reader = rightReader,
                    point = rightPoints[rightIdx]!!,
                    diffStatus = DiffStatus.ADDED,
                    outputParent = outputRoot
                )
            }
            DiffStatus.EDITED -> error("EDITED status cannot appear in raw diff")
        }
    }
}

/**
 * Calculates the diff between two lists of trace points and updates their [TRTracePoint.diffStatus] property.
 * [TRTracePoint.strictHashForDiff] is used to compare trace points.
 *
 * This implementation uses Myers' diff algorithm.
 */
private fun diffTracePointLists(leftPoints: List<TRTracePoint?>, rightPoints: List<TRTracePoint?>): List<DiffLine> {
    val ls = leftPoints.size
    val rs = rightPoints.size
    if (ls == 0 && rs == 0) return emptyList()

    val v = IntArray(2 * (ls + rs) + 1)
    val vOffset = ls + rs
    val paths = mutableListOf<IntArray>()

    v[vOffset + 1] = 0
    var found = false
    for (d in 0..(ls + rs)) {
        for (k in -d..d step 2) {
            val index = vOffset + k
            var l = if (k == -d || (k != d && v[index - 1] < v[index + 1])) {
                v[index + 1]
            } else {
                v[index - 1] + 1
            }
            var r = l - k
            while (l < ls && r < rs && leftPoints[l]?.editIndependentHashForDiff == rightPoints[r]?.editIndependentHashForDiff) {
                l++
                r++
            }
            v[index] = l
            if (l >= ls && r >= rs) {
                paths.add(v.copyOf())
                found = true
                break
            }
        }
        paths.add(v.copyOf())
        if (found) break
    }

    // Backtrack to find the path and set statuses
    var currL = ls
    var currR = rs
    val diff = mutableListOf<DiffLine>() // type, xIdx, yIdx.
    for (d in (paths.size - 1) downTo 1) {
        val vPrev = paths[d - 1]
        val k = currL - currR

        val kPrev = if (k == -d || (k != d && vPrev[vOffset + k - 1] < vPrev[vOffset + k + 1])) {
            k + 1
        } else {
            k - 1
        }

        val nextL = vPrev[vOffset + kPrev]
        val nextR = nextL - kPrev

        while (currL > nextL && currR > nextR) {
            diff.add(DiffLine(DiffStatus.UNCHANGED, currL - 1, currR - 1))
            currL--
            currR--
        }

        if (currL > nextL) {
            diff.add(DiffLine(DiffStatus.REMOVED, currL - 1, -1))
            currL--
        } else if (currR > nextR) {
            diff.add(DiffLine(DiffStatus.ADDED, -1, currR - 1))
            currR--
        }
    }
    while (currL > 0 && currR > 0) {
        diff.add(DiffLine(DiffStatus.UNCHANGED, currL - 1, currR - 1))
        currL--
        currR--
    }
    diff.reverse()
    return diff
}
