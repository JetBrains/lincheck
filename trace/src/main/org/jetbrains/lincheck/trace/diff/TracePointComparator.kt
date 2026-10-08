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

    fun editIndependentHash(tracePoint: TracePoint): Long = prepareEditIndependentHash(tracePoint).finish()

    fun strictHash(tracePoint: TracePoint): Long {
        val h = prepareEditIndependentHash(tracePoint)
        when (tracePoint) {
            is ReadArrayTracePoint -> h
                .add(tracePoint.value)
            is WriteArrayTracePoint -> h
                .add(tracePoint.value)
            is FieldTracePoint -> h
                .add(tracePoint.obj)
                .add(tracePoint.value)
            is LocalVariableTracePoint -> h
                .add(tracePoint.value)
            is LoopTracePoint -> Unit
            is LoopIterationTracePoint -> Unit
            is MethodCallTracePoint -> h
                .add(tracePoint.obj)
                .addTraceValueList(tracePoint.parameters)
                .add(tracePoint.result)
                .add(tracePoint.exceptionClassName ?: "")
            is SnapshotLineBreakpointTracePoint -> h
                .add(tracePoint.breakpointUuid.toString())
                .add(tracePoint.stackTrace) // Should we add it as-is?
                .addTraceValueList(tracePoint.locals)
                .addTraceValueList(tracePoint.watches)
                .add(tracePoint.traceId ?: "")
            is ThrowTracePoint -> h
                .add(tracePoint.exception)
            is CatchTracePoint -> h
                .add(tracePoint.exception)
            is MethodCallResultTracePoint -> h
                .add(tracePoint.result)
                .add(tracePoint.exceptionClassName ?: "")
            is LoopEndTracePoint -> h
                .add(tracePoint.iterations)
            is LoopIterationEndTracePoint -> Unit
        }
        return h.finish()
    }

    fun editIndependentEqual(a: TracePoint, b: TracePoint): Boolean =
        a.javaClass == b.javaClass && editIndependentHash(a) == editIndependentHash(b)

    fun strictEqual(a: TracePoint, b: TracePoint): Boolean =
        a.javaClass == b.javaClass && strictHash(a) == strictHash(b)

    private fun prepareEditIndependentHash(tracePoint: TracePoint): HasherMzHash64 =
        when (tracePoint) {
            // For next 3 classes value is not used in weak comparison,
            // read/write is not relevant because class is checked separately
            is ArrayTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.array)
                    .add(tracePoint.index)
            is FieldTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.className)
                    .add(tracePoint.name)
                    .add(tracePoint.isStatic)
            is LocalVariableTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.name)
            is LoopTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.loopId)
            is LoopIterationTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.loopId)
                    .add(tracePoint.loopIteration)
            // Arguments (including receiver) and result value are not used in weak comparison
            is MethodCallTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.className)
                    .add(tracePoint.methodName)
                    .add(tracePoint.flags)
                    .add(tracePoint.isStatic())
                    .add(tracePoint.returnType)
                    .add(tracePoint.argumentTypes) // It is Ok, as we use hashcode for Types.Type anyway
            // Only code location and breakpoint UUID for now
            is SnapshotLineBreakpointTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
                    .add(tracePoint.breakpointUuid.toString())
            is ThrowTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
            is CatchTracePoint ->
                hasher
                    .add(tracePoint.codeLocation)
            // The data a container's closing side carries (result, iteration count) is not used in weak comparison
            is ContainerFooterTracePoint ->
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

    private fun HasherMzHash64.add(obj: TraceValue?): HasherMzHash64 = when (obj) {
        null, TraceNull -> add("TraceNull")
        TraceVoid -> add("TraceVoid")
        TraceUnit -> add("TraceUnit")
        is TraceRedacted -> add("TraceRedacted")
            .add(obj.capturedClassName.orEmpty())
            .add(obj.templateUuid?.hashCode() ?: 0)
            .add(obj.templateName.orEmpty())
        is TraceScalar -> add("TraceScalar").add(obj.value.hashCode())
        is TraceString -> add("TraceString").add(obj.value.hashCode())
        is TraceEnum -> add(obj.className.adornedClassNameRepresentation()).add(obj.name.hashCode())
        is TraceArbitraryInteger -> add("TraceArbitraryInteger").add(obj.value.hashCode())
        is TraceArbitraryDecimal -> add("TraceArbitraryDecimal").add(obj.value.hashCode())
        is TraceTextSnapshot -> add(obj.className.adornedClassNameRepresentation()).add(obj.content.hashCode())
        is TraceObject ->
            add(obj.className.adornedClassNameRepresentation())
                .add(obj.rendered ?: "")
        is TraceObjectSnapshot ->
            add(obj.className.adornedClassNameRepresentation())
                .add(obj.rendered ?: "")
        is TraceReferenceLike -> add(obj.className.adornedClassNameRepresentation())
        is TraceTypeReference -> add("TraceTypeReference").add(obj.flavor.name).add(obj.referencedClassName.hashCode())
        is TraceRenderedValue -> add("TraceRenderedValue").add(obj.rendered.hashCode())
        TraceUnfinishedMethodResult -> add("TraceUnfinishedMethodResult")
        TraceUntrackedMethodResult -> add("TraceUntrackedMethodResult")
    }

    private fun HasherMzHash64.addTraceValueList(list: List<TraceValue?>): HasherMzHash64 {
        list.forEach { add(it) }
        return this
    }
}
