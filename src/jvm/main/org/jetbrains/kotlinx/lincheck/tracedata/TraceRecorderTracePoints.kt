/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.tracedata

import java.io.DataInput
import java.io.DataOutput
import java.util.concurrent.atomic.AtomicInteger

private val EVENT_ID_GENERATOR = AtomicInteger(0)

var INJECTIONS_VOID_OBJECT: Any? = null

sealed class TRTracePoint(
    val codeLocationId: Int,
    val threadId: Int,
    val eventId: Int
) {
   open fun save(out: DataOutput) {
        out.writeByte(getClassId(this))
        out.writeInt(codeLocationId)
        out.writeInt(threadId)
        out.writeInt(eventId)
    }

    abstract fun toText(verbose: Boolean): String
}

class TRMethodCallTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val methodId: Int,
    val obj: TRObject?,
    val parameters: List<TRObject?>,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(codeLocationId, threadId, eventId) {
    var result: TRObject? = null
    var exceptionClassName: String? = null
    @Transient
    val events: MutableList<TRTracePoint> = mutableListOf()

    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(methodId)
        out.writeTRObject(obj)
        out.writeInt(parameters.size)
        parameters.forEach {
            out.writeTRObject(it)
        }
        out.writeTRObject(result)
        out.writeUTF(exceptionClassName ?: "")
        out.writeInt(events.size)
        events.forEach {
            it.save(out)
        }
    }

    override fun toText(verbose: Boolean): String {
        val md = methodCache[methodId]
        val sb = StringBuilder()
        if (obj != null) {
            sb.append(obj.className.substringAfterLast("."))
                .append('@')
                .append(obj.identityHashCode)
        } else {
            sb.append(md.className.substringAfterLast("."))
        }
        sb.append('.')
            .append(md.methodName)
            .append('(')

        parameters.forEachIndexed { i, it ->
            if (i != 0) {
                sb.append(", ")
            }
            sb.append(it.toShortString())
        }
        sb.append(')')
        if (exceptionClassName != null) {
            sb.append(": THROWS EXCEPTION")
                .append(exceptionClassName)
        } else if (result != TR_OBJECT_VOID) {
            sb.append(": ")
            sb.append(result.toShortString())
        }
        sb.append(codeLocationId, verbose)
        return sb.toString()
    }

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRMethodCallTracePoint {
            val methodId = inp.readInt()
            val obj = inp.readTRObject()
            val pcount = inp.readInt()
            val parameters = mutableListOf<TRObject?>()
            repeat(pcount) {
                parameters.add(inp.readTRObject())
            }

            val tracePoint = TRMethodCallTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                methodId = methodId,
                obj = obj,
                parameters = parameters,
                eventId = eventId,
            )

            tracePoint.result = inp.readTRObject()
            tracePoint.exceptionClassName = inp.readUTF()
            if (tracePoint.exceptionClassName?.isEmpty() ?: false) {
                tracePoint.exceptionClassName = null
            }

            val ecount = inp.readInt()
            repeat(ecount) {
                tracePoint.events.add(loadTRTracePoint(inp))
            }

            return tracePoint
        }
    }
}

class TRReadTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val fieldId: Int,
    val obj: TRObject?,
    val value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(codeLocationId, threadId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(fieldId)
        out.writeTRObject(obj)
        out.writeTRObject(value)
    }

    override fun toText(verbose: Boolean): String {
        val fd = fieldCache[fieldId]
        val sb = StringBuilder()
        if (obj != null) {
            sb.append(obj.className.substringAfterLast("."))
                .append('@')
                .append(obj.identityHashCode)
        } else {
            sb.append(fd.className.substringAfterLast("."))
        }
        sb.append('.')
            .append(fd.fieldName)
            .append(" → ")
            .append(value.toShortString())

        sb.append(codeLocationId, verbose)
        return sb.toString()
    }

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRReadTracePoint {
            return TRReadTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                fieldId = inp.readInt(),
                obj = inp.readTRObject(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
       }
    }
}

class TRWriteTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val fieldId: Int,
    val obj: TRObject?,
    val value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(codeLocationId, threadId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(fieldId)
        out.writeTRObject(obj)
        out.writeTRObject(value)
    }

    override fun toText(verbose: Boolean): String {
        val fd = fieldCache[fieldId]
        val sb = StringBuilder()
        if (obj != null) {
            sb.append(obj.className.substringAfterLast("."))
                .append('@')
                .append(obj.identityHashCode)
        } else {
            sb.append(fd.className.substringAfterLast("."))
        }
        sb.append('.')
            .append(fd.fieldName)
            .append(" ← ")
            .append(value.toShortString())

        sb.append(codeLocationId, verbose)
        return sb.toString()
    }

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRWriteTracePoint {
            return TRWriteTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                fieldId = inp.readInt(),
                obj = inp.readTRObject(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

class TRReadLocalVariableTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val localVariableId: Int,
    val value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(codeLocationId, threadId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(localVariableId)
        out.writeTRObject(value)
    }

    override fun toText(verbose: Boolean): String {
        val vd = variableCache[localVariableId]
        val sb = StringBuilder()
        sb.append(vd.name)
            .append(" → ")
            .append(value.toShortString())

        sb.append(codeLocationId, verbose)
        return sb.toString()
    }

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRReadLocalVariableTracePoint {
            return TRReadLocalVariableTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                localVariableId = inp.readInt(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

class TRWriteLocalVariableTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val localVariableId: Int,
    val value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(codeLocationId, threadId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(localVariableId)
        out.writeTRObject(value)
    }

    override fun toText(verbose: Boolean): String {
        val vd = variableCache[localVariableId]
        val sb = StringBuilder()
        sb.append(vd.name)
            .append(" ← ")
            .append(value.toShortString())

        sb.append(codeLocationId, verbose)
        return sb.toString()
    }

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRWriteLocalVariableTracePoint {
            return TRWriteLocalVariableTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                localVariableId = inp.readInt(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

class TRReadArrayTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val array: TRObject,
    val index: Int,
    val value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(codeLocationId, threadId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeTRObject(array)
        out.writeInt(index)
        out.writeTRObject(value)
    }

    override fun toText(verbose: Boolean): String {
        val sb = StringBuilder()
        sb.append(array.className.substringAfterLast("."))
            .append('@')
            .append(array.identityHashCode)
            .append('[')
            .append(index)
            .append("] → ")
            .append(value.toShortString())

        sb.append(codeLocationId, verbose)
        return sb.toString()
    }

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRReadArrayTracePoint {
            return TRReadArrayTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                array = inp.readTRObject() ?: TR_OBJECT_NULL,
                index = inp.readInt(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

class TRWriteArrayTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val array: TRObject,
    val index: Int,
    val value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(codeLocationId, threadId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeTRObject(array)
        out.writeInt(index)
        out.writeTRObject(value)
    }

    override fun toText(verbose: Boolean): String {
        val sb = StringBuilder()
        sb.append(array.className.substringAfterLast("."))
            .append('@')
            .append(array.identityHashCode)
            .append('[')
            .append(index)
            .append("] ← ")
            .append(value.toShortString())

        sb.append(codeLocationId, verbose)
        return sb.toString()
    }

    internal companion object {
        fun load(inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRWriteArrayTracePoint {
            return TRWriteArrayTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                array = inp.readTRObject() ?: TR_OBJECT_NULL,
                index = inp.readInt(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

fun loadTRTracePoint(inp: DataInput): TRTracePoint {
    val loader = getLoaderByClassId(inp.readByte())
    val codeLocationId = inp.readInt()
    val threadId = inp.readInt()
    val eventId = inp.readInt()
    return loader(inp, codeLocationId, threadId, eventId)
}

internal val classNameCache = IndexedPool<String>()

data class TRObject(
    internal val classNameId: Int,
    val identityHashCode: Int,
) {
    constructor(className: String, identityHashCode: Int):
            this(classNameCache.getOrCreateId(className), identityHashCode)

    val className get() = classNameCache[classNameId]
}

private const val TR_OBJECT_NULL_CLASSNAME = -1
val TR_OBJECT_NULL = TRObject(TR_OBJECT_NULL_CLASSNAME, 0)

private const val TR_OBJECT_VOID_CLASSNAME = -2
val TR_OBJECT_VOID = TRObject(TR_OBJECT_VOID_CLASSNAME, 0)

fun TRObjectOrNull(obj: Any?): TRObject? =
    obj?.let { TRObject(it) }

fun TRObjectOrVoid(obj: Any?): TRObject? =
    if (obj == INJECTIONS_VOID_OBJECT) TR_OBJECT_VOID
    else TRObjectOrNull(obj)

fun TRObject(obj: Any): TRObject = TRObject(obj::class.java.name, System.identityHashCode(obj))

fun TRObject?.toShortString(): String {
    if (this == null || classNameId < 0) return "null"
    return classNameCache[classNameId].substringAfterLast(".") + "@" + identityHashCode
}

private fun DataOutput.writeTRObject(value: TRObject?) {
    val cnid = value?.classNameId ?: -1
    if (cnid < 0) {
        writeInt(cnid)
    } else {
        writeInt(value!!.classNameId)
        writeInt(value.identityHashCode)
    }
}

private fun DataInput.readTRObject(): TRObject? {
    return when (val classNameId = readInt()) {
        TR_OBJECT_NULL_CLASSNAME -> null
        TR_OBJECT_VOID_CLASSNAME -> TR_OBJECT_VOID
        else -> TRObject(classNameCache[classNameId], readInt())
    }
}

private fun <V: Appendable> V.append(codeLocationId: Int, verbose: Boolean): V {
    if (!verbose) return this
    val cl = CodeLocations.stackTrace(codeLocationId)
    append(" at ").append(cl.fileName).append(':').append(cl.lineNumber.toString())
    return this
}

private typealias TRLoader = (DataInput, Int, Int, Int) -> TRTracePoint

private fun getClassId(point: TRTracePoint): Int {
    return when (point) {
        is TRMethodCallTracePoint -> 0
        is TRReadArrayTracePoint -> 1
        is TRReadLocalVariableTracePoint -> 2
        is TRReadTracePoint -> 3
        is TRWriteArrayTracePoint -> 4
        is TRWriteLocalVariableTracePoint -> 5
        is TRWriteTracePoint -> 6
    }
}

private fun getLoaderByClassId(id: Byte): TRLoader {
    return when (id.toInt()) {
        0 -> TRMethodCallTracePoint::load
        1 -> TRReadArrayTracePoint::load
        2 -> TRReadLocalVariableTracePoint::load
        3 -> TRReadTracePoint::load
        4 -> TRWriteArrayTracePoint::load
        5 -> TRWriteLocalVariableTracePoint::load
        6 -> TRWriteTracePoint::load
        else -> error("Unknown TRTracePoint class id $id")
    }
}
