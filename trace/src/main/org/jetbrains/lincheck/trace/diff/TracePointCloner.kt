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
    val context: TraceContext,
    val threadId: Int,
    val idMapOutput: DataOutput,
    eventId: Int
) {
    var eventId = eventId
        private set

    fun generateEventId(leftId: Int = -1, rightId: Int = -1): Int {
        idMapOutput.writeInt(leftId)
        idMapOutput.writeInt(rightId)
        return eventId++
    }

    fun cloneLeftTracePoint(tracePoint: TRTracePoint, rightId: Int): TRTracePoint =
        cloneTracePoint(tracePoint, tracePoint.eventId, rightId)

    fun cloneRightTracePoint(tracePoint: TRTracePoint, leftId: Int): TRTracePoint =
        cloneTracePoint(tracePoint, leftId, tracePoint.eventId)

    fun cloneTracePoint(tracePoint: TRTracePoint, leftId: Int, rightId: Int): TRTracePoint {
        idMapOutput.writeInt(leftId)
        idMapOutput.writeInt(rightId)
        return when (tracePoint) {
            is TRReadArrayTracePoint -> TRReadArrayTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint),
                array = tracePoint.array.clone(),
                index = tracePoint.index,
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRWriteArrayTracePoint -> TRWriteArrayTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint),
                array = tracePoint.array.clone(),
                index = tracePoint.index,
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRReadTracePoint -> TRReadTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint),
                fieldId = tracePoint.fieldDescriptor.clone(),
                obj = tracePoint.obj?.clone(),
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRWriteTracePoint -> TRWriteTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint),
                fieldId = tracePoint.fieldDescriptor.clone(),
                obj = tracePoint.obj?.clone(),
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRReadLocalVariableTracePoint -> TRReadLocalVariableTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint),
                localVariableId = tracePoint.variableDescriptor.clone(),
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRWriteLocalVariableTracePoint -> TRWriteLocalVariableTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint),
                localVariableId = tracePoint.variableDescriptor.clone(),
                value = tracePoint.value?.clone(),
                eventId = eventId++
            )

            is TRLoopTracePoint -> TRLoopTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint),
                loopId = tracePoint.loopId,
                parentTracePoint = null,
                eventId = eventId++
            )

            is TRLoopIterationTracePoint -> TRLoopIterationTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint),
                loopId = tracePoint.loopId,
                loopIteration = tracePoint.loopIteration,
                parentTracePoint = null,
                eventId = eventId++
            )

            is TRMethodCallTracePoint -> TRMethodCallTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = cloneCodeLocation(tracePoint),
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

    private fun cloneCodeLocation(tracePoint: TRTracePoint): Int {
        if (tracePoint.codeLocationId == UNKNOWN_CODE_LOCATION_ID) return UNKNOWN_CODE_LOCATION_ID
        val codeLocation = tracePoint.context.codeLocation(tracePoint.codeLocationId)!!
        return context.newCodeLocation(codeLocation.stackTraceElement, codeLocation.accessPath, codeLocation.argumentNames)
    }
}
