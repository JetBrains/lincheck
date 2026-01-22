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
    fun cloneLeftTracePoint(tracePoint: TRTracePoint, rightId: Int): TRTracePoint =
        cloneTracePoint(tracePoint, tracePoint.eventId, rightId, leftCodeLocationMap)

    /**
     * Clone tracepoint from "right" source trace, add provided "left" event id to id map.
     */
    fun cloneRightTracePoint(tracePoint: TRTracePoint, leftId: Int): TRTracePoint =
        cloneTracePoint(tracePoint, leftId, tracePoint.eventId, rightCodeLocationMap)

    private fun cloneTracePoint(
        tracePoint: TRTracePoint,
        leftId: Int,
        rightId: Int,
        codeLocationMap: MutableList<Int>
    ): TRTracePoint {
        idMapOutput.writeInt(leftId)
        idMapOutput.writeInt(rightId)
        return when (tracePoint) {
            is TRReadArrayTracePoint -> TRReadArrayTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                array = tracePoint.array.clone(),
                index = tracePoint.index,
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRWriteArrayTracePoint -> TRWriteArrayTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                array = tracePoint.array.clone(),
                index = tracePoint.index,
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRReadTracePoint -> TRReadTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                fieldId = tracePoint.fieldDescriptor.clone(),
                obj = tracePoint.obj?.clone(),
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRWriteTracePoint -> TRWriteTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                fieldId = tracePoint.fieldDescriptor.clone(),
                obj = tracePoint.obj?.clone(),
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRReadLocalVariableTracePoint -> TRReadLocalVariableTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                localVariableId = tracePoint.variableDescriptor.clone(),
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRWriteLocalVariableTracePoint -> TRWriteLocalVariableTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                localVariableId = tracePoint.variableDescriptor.clone(),
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRLoopTracePoint -> TRLoopTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                loopId = tracePoint.loopId,
                parentTracePoint = null,
                eventId = eventId++
            )

            is TRLoopIterationTracePoint -> TRLoopIterationTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                loopId = tracePoint.loopId,
                loopIteration = tracePoint.loopIteration,
                parentTracePoint = null,
                eventId = eventId++
            )

            is TRMethodCallTracePoint -> TRMethodCallTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                methodId = tracePoint.methodDescriptor.clone(),
                obj = tracePoint.obj?.clone(),
                parameters = tracePoint.parameters.clone(),
                flags = tracePoint.flags,
                parentTracePoint = null,
                eventId = eventId++
            ).also {
                it.result = tracePoint.result?.clone()
                it.exceptionClassName = tracePoint.exceptionClassName
            }

            is TRSnapshotLineBreakpointTracePoint -> TRSnapshotLineBreakpointTracePoint(
                context = context,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                stackTraceCodeLocationIds = cloneCodeLocationsByIds(tracePoint, codeLocationMap, tracePoint.stackTraceCodeLocationIds),
                currentTimeMillis = tracePoint.currentTimeMillis,
                threadId = threadId,
                eventId = eventId++
            )

            is TRThrowTracePoint -> TRThrowTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                exception = tracePoint.exception.clone(),
                eventId = eventId++
            )

            is TRCatchTracePoint -> TRCatchTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint, codeLocationMap),
                exception = tracePoint.exception.clone(),
                eventId = eventId++
            )
        }
    }

    private fun TRObject.clone(): TRObject =
        if (isPrimitive) {
            TRObject(classNameId, identityHashCode, primitiveValue!!)
        } else {
            val cid = context.getOrCreateClassId(className)
            TRObject(cid, identityHashCode, context.getClassDescriptor(cid))
        }

    private fun VariableDescriptor.clone(): Int =
        context.getOrCreateVariableId(this.name, this.type)

    private fun FieldDescriptor.clone(): Int =
        context.getOrCreateFieldId(this.className, this.fieldName, this.type, this.isStatic, this.isFinal)

    private fun MethodDescriptor.clone(): Int =
        context.getOrCreateMethodId(this.className, this.methodName, this.methodSignature.methodType)

    private fun List<TRObject?>.clone(): List<TRObject?> {
        val result = mutableListOf<TRObject?>()
        forEach { result.add(it?.clone()) }
        return result
    }

    private fun AccessLocation.clone(): AccessLocation =
        when (this) {
            is LocalVariableAccessLocation -> LocalVariableAccessLocation(context.getVariableDescriptor(this.variableDescriptor.clone()))
            is StaticFieldAccessLocation -> StaticFieldAccessLocation(context.getFieldDescriptor(this.fieldDescriptor.clone()))
            is ObjectFieldAccessLocation -> ObjectFieldAccessLocation(context.getFieldDescriptor(this.fieldDescriptor.clone()))
            is ArrayElementByIndexAccessLocation -> this
            is ArrayElementByNameAccessLocation -> ArrayElementByNameAccessLocation(cloneAccessPath(this.indexAccessPath)!!)
            else -> throw IllegalArgumentException("Unsupported access location $this")
        }

    private fun cloneCodeLocation(tracePoint: TRTracePoint, codeLocationMap: MutableList<Int>): Int =
        cloneCodeLocation(tracePoint, tracePoint.codeLocationId, codeLocationMap)

    private fun cloneCodeLocation(tracePoint: TRTracePoint, srcId: Int, codeLocationMap: MutableList<Int>): Int {
        if (srcId == UNKNOWN_CODE_LOCATION_ID) return UNKNOWN_CODE_LOCATION_ID
        if (srcId < codeLocationMap.size && codeLocationMap[srcId] != UNKNOWN_CODE_LOCATION_ID) return codeLocationMap[srcId]
        val srcLoc = tracePoint.context.codeLocation(srcId)!!
        val dstId = context.newCodeLocation(srcLoc.stackTraceElement, cloneAccessPath(srcLoc.accessPath), srcLoc.argumentNames)
        addToMap(codeLocationMap, srcId, dstId)
        return dstId
    }

    private fun cloneAccessPath(accessPath: AccessPath?): AccessPath? {
        if (accessPath == null) return null
        val list = mutableListOf<AccessLocation>()
        accessPath.locations.forEach { list.add(it.clone()) }
        return AccessPath(list)
    }

    private fun cloneCodeLocationsByIds(tracePoint: TRTracePoint, codeLocationMap: MutableList<Int>, codeLocations: List<Int>): List<Int> {
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
