/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.util.toBoolean
import org.jetbrains.lincheck.util.toInt
import org.jetbrains.lincheck.util.*
import sun.nio.ch.lincheck.TestThread
import java.lang.ref.WeakReference


internal class EventStructureObjectTracker(private val eventStructure: EventStructure): BaseObjectTracker() {

    override val shouldTrackImmutableValues: Boolean = true

    private class EventStructureObjectEntry(
        objNumber: Int,
        objHashCode: Int,
        objDisplayNumber: Int,
        objReference: WeakReference<Any>,
        val allocation: AtomicThreadEvent
    ) : ObjectEntry(objNumber, objHashCode, objDisplayNumber, objReference)


    private var initEvent: AtomicThreadEvent? = null

    fun initialize(initEvent: AtomicThreadEvent) {
        require(initEvent.label is InitializationLabel)
        this.initEvent = initEvent
    }

    override fun createObjectEntry(
        objNumber: Int,
        objHashCode: Int,
        objDisplayNumber: Int,
        objReference: WeakReference<Any>,
        kind: ObjectKind
    ): ObjectEntry {
        val obj = objReference.get()!!
        if(kind == ObjectKind.EXTERNAL) {
            (initEvent!!.label as InitializationLabel).trackExternalObject(obj.javaClass.simpleName, objNumber)
            return EventStructureObjectEntry(objNumber, objHashCode, objDisplayNumber, objReference, initEvent!!)
        } else {
            val iThread = (Thread.currentThread() as? TestThread)?.threadId
            val allocationEvent = eventStructure.addObjectAllocationEvent(iThread!!,obj.opaque(), objNumber)
            return EventStructureObjectEntry(objNumber, objHashCode, objDisplayNumber, objReference, allocationEvent)
        }
        unreachable()
    }

    private fun getEventStructureEntry(id: ObjectNumber): EventStructureObjectEntry? {
        return lookupByNumber(id) as? EventStructureObjectEntry
    }

    fun getAllocation(id: ObjectNumber) : AtomicThreadEvent? {
        if(id == STATIC_OBJECT_ID) return initEvent;
        return getEventStructureEntry(id)?.allocation
    }

    fun getObject(id: ObjectNumber): OpaqueValue? {
        return lookupByNumber(id)?.objectReference?.get()?.opaque()
    }
}

internal fun EventStructureObjectTracker.registerValueIfAbsent(obj: OpaqueValue?): ObjectNumber =
    when {
        obj == null -> NULL_OBJECT_ID
        else -> registerObjectIfAbsent(obj.unwrap()).objectNumber
    }

internal fun EventStructureObjectTracker.getValue(type: Types.Type, id: ValueID): OpaqueValue? = when (type) {
    Types.LONG_TYPE       -> id.opaque()
    Types.INT_TYPE        -> id.toInt().opaque()
    Types.BYTE_TYPE       -> id.toByte().opaque()
    Types.SHORT_TYPE      -> id.toShort().opaque()
    Types.CHAR_TYPE       -> id.toInt().toChar().opaque()
    Types.BOOLEAN_TYPE    -> id.toInt().toBoolean().opaque()
    Types.LONG_TYPE_BOXED     -> id.opaque()
    Types.INT_TYPE_BOXED      -> id.toInt().opaque()
    Types.BYTE_TYPE_BOXED     -> id.toByte().opaque()
    Types.SHORT_TYPE_BOXED    -> id.toShort().opaque()
    Types.CHAR_TYPE_BOXED     -> id.toInt().toChar().opaque()
    Types.BOOLEAN_TYPE_BOXED  -> id.toInt().toBoolean().opaque()
    else                -> getObject(id.toInt())
}

internal fun EventStructureObjectTracker.getOrRegisterValueID(type: Types.Type, value: OpaqueValue?): ValueID {
    if (value == null) return NULL_OBJECT_ID.toLong()
    return when (type) {
        Types.LONG_TYPE       -> (value.unwrap() as Long)
        Types.INT_TYPE        -> (value.unwrap() as Int).toLong()
        Types.SHORT_TYPE      -> (value.unwrap() as Short).toLong()
        Types.CHAR_TYPE       -> (value.unwrap() as Char).code.toLong()
        // sometimes, due to JVM internals, boolean values can be reinterpreted as byte values
        // (e.g., because of BALOAD and BASTORE instructions are used for both boolean and byte arrays);
        // thus if the type-cast failed, we try to reinterpret the value and cast it to manually
        Types.BYTE_TYPE       ->
            (value.unwrap() as? Byte)?.toLong() ?:
            (value.unwrap() as Boolean).toInt().toLong()
        Types.BOOLEAN_TYPE    ->
            (value.unwrap() as? Boolean)?.toInt()?.toLong() ?:
            (value.unwrap() as Byte).toBoolean().toInt().toLong()
        Types.LONG_TYPE_BOXED     -> (value.unwrap() as Long)
        Types.INT_TYPE_BOXED      -> (value.unwrap() as Int).toLong()
        Types.BYTE_TYPE_BOXED     -> (value.unwrap() as Byte).toLong()
        Types.SHORT_TYPE_BOXED    -> (value.unwrap() as Short).toLong()
        Types.CHAR_TYPE_BOXED     -> (value.unwrap() as Char).code.toLong()
        Types.BOOLEAN_TYPE_BOXED  -> (value.unwrap() as Boolean).toInt().toLong()
        else                -> registerValueIfAbsent(value).toLong()
    }
}