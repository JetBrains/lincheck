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
}

class TRMethodCallTracePoint(
    threadId: Int,
    codeLocationId: Int,
    val methodId: Int,
    val obj: TRObject?,
    val parameters: List<TRObject?>,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(threadId, codeLocationId, eventId) {
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

    internal companion object {
        fun load(inp: DataInput, threadId: Int, codeLocationId: Int, eventId: Int): TRMethodCallTracePoint {
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
) : TRTracePoint(threadId, codeLocationId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(fieldId)
        out.writeTRObject(obj)
        out.writeTRObject(value)
    }

    internal companion object {
        fun load(inp: DataInput, threadId: Int, codeLocationId: Int, eventId: Int): TRReadTracePoint {
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
) : TRTracePoint(threadId, codeLocationId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(fieldId)
        out.writeTRObject(obj)
        out.writeTRObject(value)
    }

    internal companion object {
        fun load(inp: DataInput, threadId: Int, codeLocationId: Int, eventId: Int): TRWriteTracePoint {
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
) : TRTracePoint(threadId, codeLocationId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(localVariableId)
        out.writeTRObject(value)
    }

    internal companion object {
        fun load(inp: DataInput, threadId: Int, codeLocationId: Int, eventId: Int): TRReadLocalVariableTracePoint {
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
) : TRTracePoint(threadId, codeLocationId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeInt(localVariableId)
        out.writeTRObject(value)
    }

    internal companion object {
        fun load(inp: DataInput, threadId: Int, codeLocationId: Int, eventId: Int): TRWriteLocalVariableTracePoint {
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
    val array: TRObject?,
    val index: Int,
    val value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(threadId, codeLocationId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeTRObject(array)
        out.writeInt(index)
        out.writeTRObject(value)
    }

    internal companion object {
        fun load(inp: DataInput, threadId: Int, codeLocationId: Int, eventId: Int): TRReadArrayTracePoint {
            return TRReadArrayTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                array = inp.readTRObject(),
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
    val array: TRObject?,
    val index: Int,
    val value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRTracePoint(threadId, codeLocationId, eventId) {
    override fun save(out: DataOutput) {
        super.save(out)
        out.writeTRObject(array)
        out.writeInt(index)
        out.writeTRObject(value)
    }

    internal companion object {
        fun load(inp: DataInput, threadId: Int, codeLocationId: Int, eventId: Int): TRWriteArrayTracePoint {
            return TRWriteArrayTracePoint(
                threadId = threadId,
                codeLocationId = codeLocationId,
                array = inp.readTRObject(),
                index = inp.readInt(),
                value = inp.readTRObject(),
                eventId = eventId,
            )
        }
    }
}

fun loadTRTracePoint(inp: DataInput): TRTracePoint {
    val loader = getLoaderBy(inp.readByte())
    val threadId = inp.readInt()
    val codeLocationId = inp.readInt()
    val eventId = inp.readInt()
    return loader(inp, threadId, codeLocationId, eventId)
}

data class TRObject(
    val className: String,
    val hashCodeId: Int,
)

fun TRObject(obj: Any?): TRObject? =
    obj?.let { TRObject(it::class.java.name, System.identityHashCode(obj)) }

private fun DataOutput.writeTRObject(value: TRObject?) {
    if (value == null) {
        writeUTF("")
        return
    }
    writeUTF(value.className)
    writeInt(value.hashCodeId)
}

private fun DataInput.readTRObject(): TRObject? {
    val className = readUTF()
    if (className.isEmpty()) {
        return null
    }
    val hashCodeId = readInt()
    return TRObject(className, hashCodeId)
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

private fun getLoaderBy(id: Byte): TRLoader {
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
