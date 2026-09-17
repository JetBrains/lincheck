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

import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.trace.*
import java.io.DataOutput

/**
 * This class is used to clone tracepoints from one [TraceContext] to another, with all needed descriptors and such.
 *
 * These clones must be logical, not physical. It means, that all end-user information (but threadId and eventId, see
 * below) must remain the same, but ids of descriptors can be different and must refer new [TraceContext]. New tracepoint
 * must be completely independent of context of source tracepoints.
 *
 * `threadId` and `eventId` are not copied as-is, though, even though they are simple numbers and doesn't refer anything
 * in context.
 *
 *  - `threadId` is set from outside via [setThread]. Diff contains its own thread numbering, so no source threadIds
 *    are used.
 *
 *  - `eventId` is generated from scratch, with strictly incrementing counter. As `eventId` can be not unique between
 *     two source traces, it is needed to provide unique `eventId` in diff trace.
 *
 *     Also, this class saves correspondence between newly created tracepoint and its counterparts in source traces.
 *     It allows to link tracepoint in diff with tracepoints in "left" ("old") and "right" ("new") source
 *     traces. This mapping is written directly to provided [idMapOutput], as two integers. As generated
 *     event ids are sequential, there is no need to store diff event id, it is calculated by position in
 *     map. This map is logically `List<Pair<Int, Int>>` and index is `eventId` of tracepoint in diff.
 *
 *     If diff tracepoint doesn't have corresponded "left" or "right" counterparts, these absent source
 *     event ids are written as `-1`.
 *
 */
class TracePointCloner(
    private val context: TraceContext,
    private val idMapOutput: DataOutput,
) {
    private var threadId: Int = -1
    private var eventId: Int = 0
    private val leftCodeLocationMap: MutableList<Int> = mutableListOf()
    private val rightCodeLocationMap: MutableList<Int> = mutableListOf()

    fun setThread(threadId: Int) {
        this.threadId = threadId
    }

    /**
     * Provide eventId for external use and save it correspondence to provided
     * "left" and "right" ids.
     */
    fun generateEventId(leftId: Int = -1, rightId: Int = -1): Int {
        idMapOutput.writeInt(leftId)
        idMapOutput.writeInt(rightId)
        return eventId++
    }

    /**
     * Clone tracepoint from "left" source trace, add provided "right" event id to id map.
     */
    fun cloneLeftTracePoint(tracePoint: TracePoint, rightId: Int): TracePoint =
        cloneTracePoint(tracePoint, tracePoint.eventId, rightId, leftCodeLocationMap)

    /**
     * Clone tracepoint from "right" source trace, add provided "left" event id to id map.
     */
    fun cloneRightTracePoint(tracePoint: TracePoint, leftId: Int): TracePoint =
        cloneTracePoint(tracePoint, leftId, tracePoint.eventId, rightCodeLocationMap)

    private fun cloneTracePoint(
        tracePoint: TracePoint,
        leftId: Int,
        rightId: Int,
        codeLocationMap: MutableList<Int>
    ): TracePoint {
        idMapOutput.writeInt(leftId)
        idMapOutput.writeInt(rightId)
        return when (tracePoint) {
            is TraceReadArrayTracePoint -> TraceReadArrayTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                array = tracePoint.array.clone(),
                index = tracePoint.index,
                value = tracePoint.value.clone(),
                eventId = eventId++
            )

            is TraceWriteArrayTracePoint -> TraceWriteArrayTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                array = tracePoint.array.clone(),
                index = tracePoint.index,
                value = tracePoint.value.clone(),
                eventId = eventId++
            )

            is TraceReadFieldTracePoint -> TraceReadFieldTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                fieldId = tracePoint.fieldDescriptor.clone(),
                obj = tracePoint.obj.clone(),
                value = tracePoint.value.clone(),
                eventId = eventId++
            )

            is TraceWriteFieldTracePoint -> TraceWriteFieldTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                fieldId = tracePoint.fieldDescriptor.clone(),
                obj = tracePoint.obj.clone(),
                value = tracePoint.value.clone(),
                eventId = eventId++
            )

            is TraceReadLocalVariableTracePoint -> TraceReadLocalVariableTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                localVariableId = tracePoint.variableDescriptor.clone(),
                value = tracePoint.value.clone(),
                eventId = eventId++
            )

            is TraceWriteLocalVariableTracePoint -> TraceWriteLocalVariableTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                localVariableId = tracePoint.variableDescriptor.clone(),
                value = tracePoint.value.clone(),
                eventId = eventId++
            )

            is TraceLoopTracePoint -> TraceLoopTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                loopId = tracePoint.loopId,
                eventId = eventId++
            )

            is TraceLoopIterationTracePoint -> TraceLoopIterationTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                loopId = tracePoint.loopId,
                loopIteration = tracePoint.loopIteration,
                eventId = eventId++
            )

            is TraceMethodCallTracePoint -> TraceMethodCallTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                methodId = tracePoint.methodDescriptor.clone(),
                obj = tracePoint.obj.clone(),
                parameters = tracePoint.parameters.clone(),
                flags = tracePoint.flags,
                eventId = eventId++
            ).also {
                it.result = tracePoint.result.clone()
                it.exceptionClassName = tracePoint.exceptionClassName
            }

            is TraceSnapshotLineBreakpointTracePoint -> TraceSnapshotLineBreakpointTracePoint(
                context = context,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                threadId = threadId,
                breakpointUuid = tracePoint.breakpointUuid,
                stackTraceCodeLocationIds = cloneCodeLocationsByIds(tracePoint, codeLocationMap, tracePoint.stackTraceCodeLocationIds),
                currentTimeMillis = tracePoint.currentTimeMillis,
                locals = tracePoint.locals.clone(),
                watches = tracePoint.watches.clone(),
                traceId = tracePoint.traceId,
                eventId = eventId++
            )

            is TraceThrowTracePoint -> TraceThrowTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                exception = tracePoint.exception.clone(),
                eventId = eventId++
            )

            is TraceCatchTracePoint -> TraceCatchTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                exception = tracePoint.exception.clone(),
                eventId = eventId++
            )
        }
    }

    private fun TraceValue.clone(): TraceValue = when (this) {
        is TraceNull -> this
        is TraceVoid -> this
        is TraceUnit -> this
        is TraceRedacted -> copy()
        is TraceScalar -> TraceScalar(value)
        is TraceString -> TraceString(value)
        is TraceEnum -> {
            val cd = context.createAndRegisterClassDescriptor(className)
            TraceEnum(cd, name)
        }
        is TraceArbitraryInteger -> TraceArbitraryInteger(value)
        is TraceArbitraryDecimal -> TraceArbitraryDecimal(value)
        is TraceObject -> {
            val cd = context.createAndRegisterClassDescriptor(className)
            TraceObject(cd, identity, rendered)
        }
        is TraceObjectSnapshot -> {
            val cd = context.createAndRegisterClassDescriptor(className)
            TraceObjectSnapshot(cd, identity, rendered, fields.clone())
        }
        is TraceArray -> {
            val cd = context.createAndRegisterClassDescriptor(className)
            TraceArray(cd, identity, totalSize)
        }
        is TraceArraySnapshot -> {
            val cd = context.createAndRegisterClassDescriptor(className)
            TraceArraySnapshot(cd, identity, totalSize, capturedElements.clone())
        }
        is TraceMapSnapshot -> {
            val cd = context.createAndRegisterClassDescriptor(className)
            val entries = capturedEntries.map { (key, value) -> key.clone() to value.clone() }
            TraceMapSnapshot(cd, identity, totalSize, entries)
        }
        is TraceTextSnapshot -> {
            val cd = context.createAndRegisterClassDescriptor(className)
            TraceTextSnapshot(cd, identity, content)
        }
        is TraceException -> {
            val cd = context.createAndRegisterClassDescriptor(className)
            TraceException(cd, identity)
        }
        is TraceExceptionSnapshot -> {
            val cd = context.createAndRegisterClassDescriptor(className)
            TraceExceptionSnapshot(cd, identity, message, stackTrace)
        }
        is TraceTypeReference -> TraceTypeReference(referencedClassName, flavor)
        is TraceRenderedValue -> this
        is TraceUnfinishedMethodResult -> this
        is TraceUntrackedMethodResult -> this
    }

    private fun VariableDescriptor.clone(): Int =
        context.createAndRegisterVariableDescriptor(name, type).id

    private fun FieldDescriptor.clone(): Int =
        context.createAndRegisterFieldDescriptor(
            className, fieldName, type, fieldKind, isFinal, isVolatile
        ).id

    private fun MethodDescriptor.clone(): Int =
        context.createAndRegisterMethodDescriptor(
            className, methodName, methodSignature.methodType
        ).id

    private fun List<TraceValue>.clone(): List<TraceValue> = map { it.clone() }

    private fun <T> Map<T, TraceValue>.clone(): Map<T, TraceValue> = mapValues { (_, value) -> value.clone() }

    private fun AccessLocation.clone(): AccessLocation =
        when (this) {
            is LocalVariableAccessLocation -> LocalVariableAccessLocation(context.variablePool[this.variableDescriptor.clone()])
            is StaticFieldAccessLocation -> StaticFieldAccessLocation(context.fieldPool[this.fieldDescriptor.clone()])
            is ObjectFieldAccessLocation -> ObjectFieldAccessLocation(context.fieldPool[this.fieldDescriptor.clone()])
            is ArrayElementByIndexAccessLocation -> this
            is ArrayElementByNameAccessLocation -> ArrayElementByNameAccessLocation(cloneAccessPath(this.indexAccessPath)!!)
            else -> throw IllegalArgumentException("Unsupported access location $this")
        }

    private fun cloneCodeLocation(tracePoint: TracePoint, codeLocationMap: MutableList<Int>): Int =
        cloneCodeLocation(tracePoint, tracePoint.codeLocationId, codeLocationMap)

    private fun cloneCodeLocation(tracePoint: TracePoint, srcId: Int, codeLocationMap: MutableList<Int>): Int {
        if (srcId == UNKNOWN_CODE_LOCATION_ID) return UNKNOWN_CODE_LOCATION_ID
        if (srcId < codeLocationMap.size && codeLocationMap[srcId] != UNKNOWN_CODE_LOCATION_ID) return codeLocationMap[srcId]
        val dstLoc = when (val srcLoc = tracePoint.context.codeLocationsPool[srcId]) {
            is LineCodeLocation -> LineCodeLocation(srcLoc.stackTraceElement, srcLoc.activeLocals)
            is LoopHeaderCodeLocation -> LoopHeaderCodeLocation(srcLoc.stackTraceElement, srcLoc.loopIds, srcLoc.activeLocals)
            is AccessCodeLocation -> AccessCodeLocation(srcLoc.stackTraceElement, cloneAccessPath(srcLoc.accessPath), srcLoc.activeLocals)
            is MethodCallCodeLocation -> MethodCallCodeLocation(srcLoc.stackTraceElement, cloneAccessPath(srcLoc.accessPath), srcLoc.argumentNames?.map { cloneAccessPath(it) }, srcLoc.activeLocals)
        }
        val dstId = context.codeLocationsPool.register(dstLoc)
        addToMap(codeLocationMap, srcId, dstId)
        return dstId
    }

    private fun cloneAccessPath(accessPath: AccessPath?): AccessPath? {
        if (accessPath == null) return null
        val list = accessPath.locations.map { it.clone() }
        return AccessPath(list)
    }

    private fun cloneCodeLocationsByIds(tracePoint: TracePoint, codeLocationMap: MutableList<Int>, codeLocations: List<Int>): List<Int> {
        val result = mutableListOf<Int>()
        codeLocations.forEach { srcId -> result.add(cloneCodeLocation(tracePoint, srcId, codeLocationMap)) }
        return result
    }

    private fun addToMap(map: MutableList<Int>, key: Int, value: Int) {
        while (map.size <= key) {
            map.add(UNKNOWN_CODE_LOCATION_ID)
        }
        map[key] = value
    }
}
