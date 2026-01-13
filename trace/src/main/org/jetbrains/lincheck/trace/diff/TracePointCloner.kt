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

import org.jetbrains.lincheck.descriptors.FieldDescriptor
import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.VariableDescriptor
import org.jetbrains.lincheck.trace.*
import java.io.DataOutput

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

    fun generateEventId(leftId: Int = -1, rightId: Int = -1): Int {
        idMapOutput.writeInt(leftId)
        idMapOutput.writeInt(rightId)
        return eventId++
    }

    fun cloneLeftTracePoint(tracePoint: TRTracePoint, rightId: Int): TRTracePoint =
        cloneTracePoint(tracePoint, tracePoint.eventId, rightId, leftCodeLocationMap)

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
        context.getOrCreateVariableId(this.name)

    private fun FieldDescriptor.clone(): Int =
        context.getOrCreateFieldId(this.className, this.fieldName, this.isStatic, this.isFinal)

    private fun MethodDescriptor.clone(): Int =
        context.getOrCreateMethodId(this.className, this.methodName, this.methodSignature.methodType)

    private fun List<TRObject?>.clone(): List<TRObject?> {
        val result = mutableListOf<TRObject?>()
        forEach { result.add(it?.clone()) }
        return result
    }

    private fun cloneCodeLocation(tracePoint: TRTracePoint, codeLocationMap: MutableList<Int>): Int =
        cloneCodeLocation(tracePoint, tracePoint.codeLocationId, codeLocationMap)

    private fun cloneCodeLocation(tracePoint: TRTracePoint, srcId: Int, codeLocationMap: MutableList<Int>): Int {
        if (srcId == UNKNOWN_CODE_LOCATION_ID) return UNKNOWN_CODE_LOCATION_ID
        if (srcId < codeLocationMap.size && codeLocationMap[srcId] != UNKNOWN_CODE_LOCATION_ID) return codeLocationMap[srcId]
        val srcLoc = tracePoint.context.codeLocation(srcId)!!
        val dstId = context.newCodeLocation(srcLoc.stackTraceElement, srcLoc.accessPath, srcLoc.argumentNames)
        addToMap(codeLocationMap, srcId, dstId)
        return dstId
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
