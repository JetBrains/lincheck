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
import org.jetbrains.kotlinx.lincheck.util.ThreadId
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.util.toBoolean
import org.jetbrains.lincheck.util.toInt
import sun.nio.ch.lincheck.TestThread
import org.jetbrains.lincheck.util.*


internal class ObjectRegistry(private val eventStructure: EventStructure): BaseObjectTracker() {

    var allocationMap: MutableMap<ObjectID, AtomicThreadEvent> = mutableMapOf()
    var objMap: MutableMap<ObjectID, OpaqueValue> = mutableMapOf()

    var primitiveMap: MutableMap<ValueID, OpaqueValue> = mutableMapOf()

    private var initEvent: AtomicThreadEvent? = null

    fun initialize(initEvent: AtomicThreadEvent) {
        require(initEvent.label is InitializationLabel)
        this.initEvent = initEvent
    }

    override fun registerExternalObject(obj: Any): ObjectEntry {
        return super.registerExternalObject(obj).also {
            val objectID = it.objectId;
            check(allocationMap.containsKey(objectID) == objMap.containsKey(objectID))
            if(allocationMap.containsKey(objectID)) {
                return@also
            }

            // External object are related to the
            allocationMap[objectID] = initEvent!!
            // TODO: See if we need this
            (initEvent!!.label as InitializationLabel).trackExternalObject(obj.javaClass.simpleName, objectID)
            objMap[objectID] = obj.opaque()
        }
    }

    override fun registerNewObject(obj: Any): ObjectEntry {
        return super.registerNewObject(obj).also { addEntries(obj, it.objectId) }
    }

    fun registerTestInstance(obj: Any, threadId: ThreadId): ObjectEntry {
        return super.registerExternalObject(obj).also { addEntries(obj, it.objectId, threadId) }
    }

    private fun addEntries(obj: Any, objectID: ObjectID, iThread: ThreadId? = null ) {
        val iThread = iThread ?: (Thread.currentThread() as? TestThread)?.threadId
        if (iThread != null) {
            val allocationEvent = eventStructure.addObjectAllocationEvent(iThread,obj.opaque(), objectID)
            allocationMap[objectID] = allocationEvent
            objMap[objectID] = obj.opaque()
        }
    }

    fun getAllocation(id: ObjectID) : AtomicThreadEvent? {
        if(id == STATIC_OBJECT_ID) return initEvent;
        return allocationMap[id]
    }

    // TODO: This is a bit stupid since we have no way to tell from just
    // the ObjectID if we have an immutable value or an object
    fun getObject(id: ObjectID): OpaqueValue? {
        return primitiveMap[id] ?: objMap[id]
    }
}


// Any -> UniqueStableId
// if primitive (Int, Long ...) or value type -> something based on (
// if value (something based on ) ->

internal fun ObjectRegistry.getOrRegisterObjectID(obj: OpaqueValue?): ObjectID =
    when {
        obj == null -> NULL_OBJECT_ID
        obj.unwrap().isImmutable -> getOrRegisterPrimitveValue(obj)
        else -> registerObjectIfAbsent(obj.unwrap()).objectId
    }

internal fun ObjectRegistry.getValue(type: Types.Type, id: ValueID): OpaqueValue? = when (type) {
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
    else                -> getObject(id)
}

//internal fun ObjectRegistry.getValueID(type: Types.Type, value: OpaqueValue?): ValueID {
//    if (value == null) return NULL_OBJECT_ID
//    return when (type) {
//        Types.LONG_TYPE       -> (value.unwrap() as Long)
//        Types.INT_TYPE        -> (value.unwrap() as Int).toLong()
//        Types.BYTE_TYPE       -> (value.unwrap() as Byte).toLong()
//        Types.SHORT_TYPE      -> (value.unwrap() as Short).toLong()
//        Types.CHAR_TYPE       -> (value.unwrap() as Char).code.toLong()
//        Types.BOOLEAN_TYPE    -> (value.unwrap() as Boolean).toInt().toLong()
//        Types.LONG_TYPE_BOXED     -> (value.unwrap() as Long)
//        Types.INT_TYPE_BOXED      -> (value.unwrap() as Int).toLong()
//        Types.BYTE_TYPE_BOXED     -> (value.unwrap() as Byte).toLong()
//        Types.SHORT_TYPE_BOXED    -> (value.unwrap() as Short).toLong()
//        Types.CHAR_TYPE_BOXED     -> (value.unwrap() as Char).code.toLong()
//        Types.BOOLEAN_TYPE_BOXED  -> (value.unwrap() as Boolean).toInt().toLong()
//        else                -> get(value)?.id ?: NULL_OBJECT_ID
//    }
//}

internal fun ObjectRegistry.getOrRegisterValueID(type: Types.Type, value: OpaqueValue?): ValueID {
    if (value == null) return NULL_OBJECT_ID
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
        else                -> getOrRegisterObjectID(value)
    }
}