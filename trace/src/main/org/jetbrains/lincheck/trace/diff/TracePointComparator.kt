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

internal object TracePointComparator {
    private val hasher = HasherMzHash64()

    fun editIndependentHash(tracePoint: TRTracePoint): Long = prepareEditIndependentHash(tracePoint).finish()

    fun strictHash(tracePoint: TRTracePoint): Long {
        val h = prepareEditIndependentHash(tracePoint)
        when (tracePoint) {
            is TRReadArrayTracePoint -> h
                .add(tracePoint.value)
            is TRWriteArrayTracePoint -> h
                .add(tracePoint.value)
            is TRFieldTracePoint -> h
                .add(tracePoint.obj)
                .add(tracePoint.value)
            is TRLocalVariableTracePoint -> h
                .add(tracePoint.value)
            is TRLoopTracePoint -> Unit
            is TRLoopIterationTracePoint -> Unit
            is TRMethodCallTracePoint -> h
                .add(tracePoint.obj)
                .addTRList(tracePoint.parameters)
                .add(tracePoint.result)
                .add(tracePoint.exceptionClassName ?: "")
            is TRSnapshotLineBreakpointTracePoint -> h
                .add(tracePoint.stackTrace) // Should we add it as-is?
        }
        return h.finish()
    }

    fun editIndependentEqual(a: TRTracePoint, b: TRTracePoint): Boolean =
        a.javaClass == b.javaClass && editIndependentHash(a) == editIndependentHash(b)

    fun strictEqual(a: TRTracePoint, b: TRTracePoint): Boolean =
        a.javaClass == b.javaClass && strictHash(a) == strictHash(b)

    private fun prepareEditIndependentHash(tracePoint: TRTracePoint): HasherMzHash64 =
        when (tracePoint) {
            // For next 3 classes value is not used in weak comparison,
            // read/write is not relevant because class is checked separately
            is TRArrayTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.array)
                    .add(tracePoint.index)
            is TRFieldTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.className)
                    .add(tracePoint.name)
                    .add(tracePoint.isStatic)
            is TRLocalVariableTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.name)
            is TRLoopTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.loopId)
            is TRLoopIterationTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.loopId)
                    .add(tracePoint.loopIteration)
            // Arguments (including receiver) and result value are not used in weak comparison
            is TRMethodCallTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.className)
                    .add(tracePoint.methodName)
                    .add(tracePoint.flags)
                    .add(tracePoint.isStatic())
                    .add(tracePoint.returnType)
                    .add(tracePoint.argumentTypes) // It is Ok, as we use hashcode for Types.Type anyway
            // Only code location for now
            is TRSnapshotLineBreakpointTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
        }

    // All tracepoint hashes includes code location.
    // Two tracepoints at different locations are different, we try to compare
    // Tracepoints from two runs over exactly same sources
    private fun HasherMzHash64.add(codeLocation: StackTraceElement): HasherMzHash64 =
        add(codeLocation.fileName ?: "").add(codeLocation.lineNumber)

    private fun HasherMzHash64.add(type: Types.Type): HasherMzHash64 =
        add(type.hashCode())

    private fun HasherMzHash64.add(obj: TRObject?): HasherMzHash64 =
        if (obj == null) add(TR_OBJECT_NULL)
        else if (obj.isPrimitive) add(obj.classNameId).add(obj.primitiveValue.hashCode())
        else if (obj.classNameId < 0) add(obj.classNameId)
        else add(obj.className.adornedClassNameRepresentation())

    private fun HasherMzHash64.addTRList(list: List<TRObject?>): HasherMzHash64 {
        list.forEach { add(it) }
        return this
    }
}
