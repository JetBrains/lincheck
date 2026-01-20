/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace

import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.lincheck.util.indexOf
import org.jetbrains.lincheck.util.indexOfLast
import org.jetbrains.lincheck.util.move
import org.jetbrains.lincheck.util.subList

/**
 * Adjusts the positions of `SwitchEventTracePoint` instances within the trace,
 * moving the switch points occurring at the beginning of methods outside the method call trace points.
 *
 * For instance, the following trace sequence:
 *   ```
 *   foo()
 *      bar()
 *         switch
 *         ...
 *         x = 42
 *   ```
 *
 *  will be transformed into:
 *    ```
 *    switch
 *    ...
 *    foo()
 *      bar()
 *        x = 42
 *    ```
 *
 * @return A new trace instance with updated trace point ordering.
 */
internal fun Trace.moveStartingSwitchPointsOutOfMethodCalls(): Trace {
    val newTrace = this.trace.toMutableList()
    val tracePointsToRemove = mutableListOf<IntRange>()

    for (currentPosition in newTrace.indices) {
        val tracePoint = newTrace[currentPosition]
        if (tracePoint !is SwitchEventTracePoint) continue

        // find a place where to move the switch point
        var switchMovePosition = newTrace.indexOfLast(from = currentPosition - 1) {
            !(
                (it is MethodCallTracePoint &&
                    // do not move the switch out of `Thread.start()`
                    !it.isThreadStart() &&
                    // do not move the switch out of suspend method calls
                    !it.isSuspend
                ) ||
                (it is SpinCycleStartTracePoint)
            )
        }
        if (++switchMovePosition == currentPosition) continue

        // find the next section of the thread we are switching from
        // to move the remaining method call trace points there
        val currentThreadNextTracePointPosition = newTrace.indexOf(from = currentPosition + 1) {
            it.iThread == tracePoint.iThread
        }

        // move switch point before method calls
        newTrace.move(currentPosition, switchMovePosition)

        val movedTracePointsRange = IntRange(switchMovePosition + 1, currentPosition)
        val movedTracePoints = newTrace.subList(movedTracePointsRange)
        val methodCallTracePoints = movedTracePoints.filter { it is MethodCallTracePoint }
        val remainingTracePoints = newTrace.subList(currentThreadNextTracePointPosition, newTrace.size)
            .filter { it.iThread == tracePoint.iThread }
        val shouldRemoveRemainingTracePoints = remainingTracePoints.all {
            (it is MethodCallTracePoint && it.isActor) ||
            (it is MethodReturnTracePoint) ||
            it is SpinCycleStartTracePoint
        }
        val isThreadJoinSwitch = (movedTracePoints.lastOrNull()?.isThreadJoin() == true) &&
            // check that Thread.join() call has return value set, otherwise it is a hung join
            (movedTracePoints.last() as MethodCallTracePoint).returnedValue != ReturnedValueResult.NoValue

        if (currentThreadNextTracePointPosition == newTrace.size || shouldRemoveRemainingTracePoints && !isThreadJoinSwitch) {
            // handle the case when the switch point is the last event in the thread
            val methodReturnTracePointsRange = if (methodCallTracePoints.isNotEmpty())
                IntRange(currentThreadNextTracePointPosition, currentThreadNextTracePointPosition + methodCallTracePoints.size - 1)
                else IntRange.EMPTY
            tracePointsToRemove.add(movedTracePointsRange)
            tracePointsToRemove.add(methodReturnTracePointsRange)
        } else {
            // else move method call trace points to the next trace section of the current thread
            newTrace.move(movedTracePointsRange, currentThreadNextTracePointPosition)
        }
    }

    for (i in tracePointsToRemove.indices.reversed()) {
        val range = tracePointsToRemove[i]
        if (range.isEmpty()) continue
        newTrace.subList(range).clear()
    }

    return Trace(newTrace, this.threadNames)
}

/**
 * Adjusts the positions of `SpinCycleStartTracePoint` instances within the trace
 * corresponding to recursive spin-locks, ensuring that the recursive spin-lock is marked
 * at the point of the recursive method call trace point.
 *
 * For instance, the following trace sequence:
 *   ```
 *   foo()
 *     bar()
 *       /* spin loop start */
 *   ┌╶> x = 42
 *   |   ...
 *   └╶╶ switch
 *     ...
 *     foo()
 *        bar()
 *          /* spin loop start */
 *      ┌╶> x = 42
 *      |   ...
 *      └╶╶ switch
 *   ```
 *
 *  will be transformed into:
 *   ```
 *       /* spin loop start */
 *   ┌╶> foo()
 *   |     bar()
 *   |       x = 42
 *   |       ...
 *   └╶╶---- switch
 *     ...
 *           /* spin loop start */
 *       ┌╶> foo()
 *       |     bar()
 *       |       x = 42
 *       |       ...
 *       └╶╶---- switch
 *   ```
 *
 * @return A new trace instance with updated trace point ordering.
 */

//TODO: refactor to work for new loop detector
internal fun Trace.moveSpinCycleStartTracePoints(): Trace {
    val newTrace = this.trace.toMutableList()

    for (currentPosition in newTrace.indices) {
        val tracePoint = newTrace[currentPosition]
        if (tracePoint !is SpinCycleStartTracePoint) continue

        check(currentPosition > 0)

        // find a beginning of the current thread section
        var currentThreadSectionStartPosition = newTrace.indexOfLast(from = currentPosition - 1) {
            it.iThread != tracePoint.iThread
        }
        ++currentThreadSectionStartPosition

        // find the next thread switch
        val nextThreadSwitchPosition = newTrace.indexOf(from = currentPosition + 1) {
            it is SwitchEventTracePoint || it is ObstructionFreedomViolationExecutionAbortTracePoint
        }
        if (nextThreadSwitchPosition == currentPosition + 1) continue

        // compute call stack traces
        val currentStackTrace = mutableListOf<MethodCallTracePoint>()
        val stackTraces = mutableListOf<List<MethodCallTracePoint>>()
        for (n in currentThreadSectionStartPosition .. currentPosition) {
            stackTraces.add(currentStackTrace.toList())
            if (newTrace[n] is MethodCallTracePoint) {
                currentStackTrace.add(newTrace[n] as MethodCallTracePoint)
            } else if (newTrace[n] is MethodReturnTracePoint) {
                currentStackTrace.removeLastOrNull()
            }
        }

        // compute the patched stack trace of the spin cycle trace point
        val spinCycleStartStackTrace = tracePoint.callStackTrace
//        val spinCycleStartPatchedStackTrace = recomputeSpinCycleStartCallStack(
//            spinCycleStartTracePoint = newTrace[currentPosition],
//            spinCycleEndTracePoint = newTrace[nextThreadSwitchPosition],
//        )
        val stackTraceElementsDropCount = spinCycleStartStackTrace.size //- spinCycleStartPatchedStackTrace.size
        val spinCycleStartStackTraceSize = stackTraces.lastOrNull()?.size ?: 0
        val callStackSize = (spinCycleStartStackTraceSize - stackTraceElementsDropCount).coerceAtLeast(0)

        // find the position where to move the spin cycle start trace point
        val i = currentPosition
        var j = currentPosition
        val k = currentThreadSectionStartPosition
        while (j >= k && stackTraces[j - k].size > callStackSize) {
            --j
        }

        newTrace.move(i, j)
    }

    return Trace(newTrace, this.threadNames)
}

internal fun Trace.removeRedundantSectionDelimiters(): Trace {
    val firstTracePoint = trace.firstOrNull() ?: return this
    if (firstTracePoint !is SectionDelimiterTracePoint) return this
    if (trace.count { it is SectionDelimiterTracePoint } > 1) return this
    return Trace(trace.drop(1), threadNames)
}

/**
 * Removes the validation section from the trace.
 *
 * @return a new trace with the validation section removed if applicable,
 *   or the original trace if no removal is necessary.
 */
internal fun Trace.removeValidationSection(): Trace {
    val newTrace = this.trace.takeWhile {
        !(it is SectionDelimiterTracePoint && it.executionPart == ExecutionPart.VALIDATION)
    }
    return Trace(newTrace, this.threadNames)
}

/**
 * Assigns an exception number for each `ActorExceptionResult` in the trace.
 * To be consistent with the reported exceptions.
 * The numbering is based on the actor order, sorted by `actorId` and `iThread`.
 *
 * @return A new `Trace` instance with updated exception numbers for `ActorExceptionResult` instances.
 */
internal fun Trace.numberExceptionResults(): Trace = this.deepCopy().also { copy ->
    copy.trace
        .filterIsInstance<MethodCallTracePoint>()
        .filter { it.isActor }
        .sortedWith (compareBy({ it.actorId }, { it.iThread }))
        .map { it.returnedValue }
        .filterIsInstance<ReturnedValueResult.ExceptionResult>()
        .forEachIndexed { index, exceptionResult -> exceptionResult.exceptionNumber = index + 1 }
}

/**
 * Removes artificial GPMC actor method call from the trace and adjusts the trace structure.
 *
 * @return A new trace with the GPMC actor method call removed.
 * @throws IllegalStateException If the input trace does not represent GPMC run trace.
 */
// TODO support multiple root nodes in GPMC mode, needs discussion on how to deal with `result: ...`
internal fun Trace.removeGPMCLambda(): Trace {
    val newTrace = this.trace.toMutableList()

    fun TracePoint.isGPMCRunMethodCall(): Boolean =
        this is MethodCallTracePoint &&
        this.callType == MethodCallTracePoint.CallType.THREAD_RUN

    check(newTrace[0].isGPMCRunMethodCall()) {
        "In GPMC trace the first trace point must be a run() method call"
    }
    check(newTrace[0].eventId == 0) {
        "In GPMC trace the first trace point must be a run() method call with eventId = 0, " +
        "actual eventId = ${newTrace[0].eventId}"
    }

    val gpmcCallIndex = 0
    val gpmcResultIndex = newTrace.indexOfFirst {
        it is MethodReturnTracePoint && it.methodTracePoint.isGPMCRunMethodCall() && it.methodTracePoint.eventId == 0
    }
    check(gpmcResultIndex >= 0) {
        "GPMC trace is expected"
    }

    newTrace.removeAt(gpmcResultIndex)
    newTrace.removeAt(gpmcCallIndex)

    return Trace(newTrace, this.threadNames)
}

/**
 * Filters out empty "hung" actor nodes from the list of trace nodes.
 *
 * @return A new list of [TraceNode] instances with all empty "hung" actor nodes removed.
 */
internal fun List<TraceNode>.removeEmptyHungActors(): List<TraceNode> {
    return filterNot { node ->
        node is CallNode &&
        node.isActor &&
        node.children.isEmpty() &&
        // TODO: use special result object to denote hung result
        node.tracePoint.returnedValue is ReturnedValueResult.NoValue
    }
}

/**
 * Appends actor result nodes [ResultNode] to the list of [TraceNode] elements where applicable.
 */
internal fun List<TraceNode>.appendResultNodes() {
    val nodes = this
    for (node in nodes) {
        if (node !is CallNode || !node.isRootCall || !node.isActor) continue

        val returnedValue = node.tracePoint.returnedValue

        // Do not add an empty hung actor
        if (node.isActor &&
            node.children.size == 1 &&
            returnedValue is ReturnedValueResult.NoValue
        ) continue

        // Do not add a result node for an actor with no children
        if (node.isActor &&
            node.children.isEmpty()
        ) continue

        if (!returnedValue.showAtMethodCallEnd) continue

        val resultNode = ResultNode(
            returnedValue,
            node.returnEventNumber,
            node.tracePoint,
        )
        node.addChild(resultNode)
    }
}