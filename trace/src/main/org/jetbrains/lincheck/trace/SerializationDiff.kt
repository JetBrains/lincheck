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

data class DiffLine(val status: DiffStatus, val leftIdx: Int, val rightIDx: Int)

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
                val rightStatus = if (lp.strictHashForDiff == rp.strictHashForDiff) DiffStatus.UNCHANGED else DiffStatus.ADDED

                if (lp.strictHashForDiff != rp.strictHashForDiff) {
                    // "Remove" left and make it without children, add right
                    val removedPoint = lp.cloneToNewContext(outputContext, outputThreadId)
                    removedPoint.diffStatus = DiffStatus.REMOVED
                    outputRoot.addChild(removedPoint)
                    removedPoint.save(output)
                    if (removedPoint is TRContainerTracePoint) {
                        removedPoint.saveFooter(output)
                    }
                }
                // Copy tracepoint itself from right subtree for now
                val outputPoint = rp.cloneToNewContext(outputContext, outputThreadId)
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
 * Calculates the diff between two lists and updates their [TRTracePoint.diffStatus] property.
 *
 * This implementation uses Myers' diff algorithm.
 */
private fun <T> diffLists(left: List<T>, right: List<T>, equals: (T, T) -> Boolean): List<DiffLine> {
    val n = left.size
    val m = right.size
    if (n == 0 && m == 0) return emptyList()

    val max = n + m
    val v = IntArray(2 * max + 1)
    val trace = mutableListOf<IntArray>()

    v[max + 1] = 0

    var x: Int
    var y: Int
    outer@ for (d in 0..max) {
        val currentV = v.copyOf()
        trace.add(currentV)
        for (k in -d..d step 2) {
            val idx = k + max
            if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                x = v[idx + 1]
            } else {
                x = v[idx - 1] + 1
            }
            y = x - k
            while (x < n && y < m && equals(left[x], right[y])) {
                x++
                y++
            }
            v[idx] = x
            if (x >= n && y >= m) {
                break@outer
            }
        }
    }

    val result = mutableListOf<DiffLine>()
    x = n
    y = m

    for (d in trace.size - 1 downTo 0) {
        val k = x - y
        val idx = k + max
        val currentV = trace[d]

        val prevK = if (k == -d || (k != d && currentV[idx - 1] < currentV[idx + 1])) {
            k + 1
        } else {
            k - 1
        }
        val prevX = currentV[prevK + max]
        val prevY = prevX - prevK

        while (x > prevX && y > prevY) {
            result.add(DiffLine(DiffStatus.UNCHANGED, x - 1, y - 1))
            x--
            y--
        }

        if (d > 0) {
            if (x > prevX) {
                result.add(DiffLine(DiffStatus.REMOVED, x - 1, -1))
                x--
            } else if (y > prevY) {
                result.add(DiffLine(DiffStatus.ADDED, -1, y - 1))
                y--
            }
        }
    }

    return result.reversed()
}

private fun diffTracePointLists(leftPoints: List<TRTracePoint?>, rightPoints: List<TRTracePoint?>): List<DiffLine> =
    diffLists(leftPoints, rightPoints) { l, r -> l!!.editedEquals(r!!) }
