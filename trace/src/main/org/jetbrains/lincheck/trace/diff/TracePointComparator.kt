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
    private val hasher = Hasher()

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

    private fun prepareEditIndependentHash(tracePoint: TRTracePoint): Hasher =
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
    private fun Hasher.add(codeLocation: StackTraceElement): Hasher =
        add(codeLocation.fileName ?: "").add(codeLocation.lineNumber)

    private fun Hasher.add(type: Types.Type): Hasher =
        add(type.hashCode())

    private fun Hasher.add(obj: TRObject?): Hasher =
        if (obj == null) add(TR_OBJECT_NULL)
        else if (obj.isPrimitive) add(obj.classNameId).add(obj.primitiveValue.hashCode())
        else if (obj.classNameId < 0) add(obj.classNameId)
        else add(obj.className.adornedClassNameRepresentation())

    private fun Hasher.addTRList(list: List<TRObject?>): Hasher {
        list.forEach { add(it) }
        return this
    }
}

// mzHash64 instead of standard Java hash to expand it to long
// https://github.com/matteo65/mzHash64
private class Hasher {
    private companion object {
        const val START: Long = -2390164861889055616L // 0xDED46DB8C47B7480L
        const val MUL: Long = -1632365092264590397 // 0xE958AC98E3D243C3L
    }

    private var hash: Long = START

    fun add(v: Byte): Hasher {
        updateHash(v.toInt())
        return this
    }

    fun add(v: Boolean): Hasher {
        updateHash(if (v) 1 else 0)
        return this
    }

    fun add(v: Char): Hasher {
        val i = v.code
        updateHash((i      ) and 0xff)
        updateHash((i shr 8) and 0xff)
        return this
    }

    fun add(v: Short): Hasher {
        updateHash((v.toInt()      ) and 0xff)
        updateHash((v.toInt() shr 8) and 0xff)
        return this
    }

    fun add(v: Int): Hasher {
        updateHash((v       ) and 0xff)
        updateHash((v shr  8) and 0xff)
        updateHash((v shr 16) and 0xff)
        updateHash((v shr 24) and 0xff)
        return this
    }

    fun add(v: Long): Hasher {
        updateHash(((v       ) and 0xffffffffL).toInt())
        updateHash(((v shr 32) and 0xffffffffL).toInt())
        return this
    }

    fun add(v: String): Hasher {
        v.forEach {
            val i = it.code
            updateHash((i      ) and 0xff)
            updateHash((i shr 8) and 0xff)
        }
        return this
    }

    // fun add(v: Any): Hasher = add(v.hashCode())

    fun <T> add(v: Collection<T>): Hasher {
        v.forEach { add(it.hashCode()) }
        return this
    }

    fun finish(): Long {
        val h = hash
        hash = START
        return h
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun updateHash(v: Int) {
        val h = hash
        hash = MUL * (v.toLong() xor (h shl 2) xor (h shr 2))
    }
}