/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.ensureNull
import kotlin.collections.HashMap
import java.util.IdentityHashMap
import org.objectweb.asm.Type


data class ObjectEntry(
    val id: ObjectID,
    val obj: OpaqueValue,
    val allocation: AtomicThreadEvent,
) {
    init {
        require(id != NULL_OBJECT_ID)
        require(allocation.label is InitializationLabel || allocation.label is ObjectAllocationLabel)
        require((id == STATIC_OBJECT_ID || obj.isPrimitive()) implies (allocation.label is InitializationLabel))
    }

    val isExternal: Boolean
        get() = (allocation.label is InitializationLabel)

}

class ObjectRegistry {

    private var objectCounter = 0L

    private val objectIdIndex = HashMap<ObjectID, ObjectEntry>()

    private val objectIndex = IdentityHashMap<Any, ObjectEntry>()
    private val primitiveIndex = HashMap<Any, ObjectEntry>()

    val nextObjectID: ObjectID
        get() = 1 + objectCounter

    private var initEvent: AtomicThreadEvent? = null

    fun initialize(initEvent: AtomicThreadEvent) {
        require(initEvent.label is InitializationLabel)
        this.initEvent = initEvent
    }

    fun register(entry: ObjectEntry) {
        check(entry.id != NULL_OBJECT_ID)
        check(entry.id <= objectCounter + 1)
        check(!entry.obj.isPrimitive)
        objectIdIndex.put(entry.id, entry).ensureNull()
        objectIndex.put(entry.obj.unwrap(), entry).ensureNull()
        if (entry.id != STATIC_OBJECT_ID) {
            objectCounter++
        }
    }

    private fun registerPrimitiveObject(obj: OpaqueValue): ObjectID {
        check(obj.isPrimitive)
        val entry = primitiveIndex.computeIfAbsent(obj.unwrap()) {
            val id = ++objectCounter
            val entry = ObjectEntry(id, obj, initEvent!!)
            objectIdIndex.put(entry.id, entry).ensureNull()
            return@computeIfAbsent entry
        }
        return entry.id
    }

    private fun registerExternalObject(obj: OpaqueValue): ObjectID {
        if (obj.isPrimitive) {
            return registerPrimitiveObject(obj)
        }
        val id = nextObjectID
        val entry = ObjectEntry(id, obj, initEvent!!)
        register(entry)
        return id
    }

    fun getOrRegisterObjectID(obj: OpaqueValue): ObjectID {
        get(obj)?.let { return it.id }
        val className = obj.unwrap().javaClass.simpleName
        val id = registerExternalObject(obj)
        (initEvent!!.label as InitializationLabel).trackExternalObject(className, id)
        return id
    }

    operator fun get(id: ObjectID): ObjectEntry? =
        objectIdIndex[id]

    operator fun get(obj: OpaqueValue): ObjectEntry? =
        if (obj.isPrimitive) primitiveIndex[obj.unwrap()] else objectIndex[obj.unwrap()]

    fun retain(predicate: (ObjectEntry) -> Boolean) {
        objectIdIndex.values.retainAll(predicate)
        objectIndex.values.retainAll(predicate)
        primitiveIndex.values.retainAll(predicate)
    }

}

fun ObjectRegistry.getOrRegisterObjectID(obj: OpaqueValue?): ObjectID =
    if (obj == null) NULL_OBJECT_ID else getOrRegisterObjectID(obj)

fun ObjectRegistry.getValue(type: Type, id: ValueID): OpaqueValue? = when (type.sort) {
    Type.LONG       -> id.opaque()
    Type.INT        -> id.toInt().opaque()
    Type.BYTE       -> id.toByte().opaque()
    Type.SHORT      -> id.toShort().opaque()
    Type.CHAR       -> id.toChar().opaque()
    Type.BOOLEAN    -> id.toInt().toBoolean().opaque()
    else            -> when (type) {
        LONG_TYPE_BOXED     -> id.opaque()
        INT_TYPE_BOXED      -> id.toInt().opaque()
        BYTE_TYPE_BOXED     -> id.toByte().opaque()
        SHORT_TYPE_BOXED    -> id.toShort().opaque()
        CHAR_TYPE_BOXED     -> id.toChar().opaque()
        BOOLEAN_TYPE_BOXED  -> id.toInt().toBoolean().opaque()
        else                -> get(id)?.obj
    }
}

fun ObjectRegistry.getValueID(type: Type, value: OpaqueValue?): ValueID {
    if (value == null) return NULL_OBJECT_ID
    return when (type.sort) {
        Type.LONG       -> (value.unwrap() as Long)
        Type.INT        -> (value.unwrap() as Int).toLong()
        Type.BYTE       -> (value.unwrap() as Byte).toLong()
        Type.SHORT      -> (value.unwrap() as Short).toLong()
        Type.CHAR       -> (value.unwrap() as Char).toLong()
        Type.BOOLEAN    -> (value.unwrap() as Boolean).toInt().toLong()
        else            -> when (type) {
            LONG_TYPE_BOXED     -> (value.unwrap() as Long)
            INT_TYPE_BOXED      -> (value.unwrap() as Int).toLong()
            BYTE_TYPE_BOXED     -> (value.unwrap() as Byte).toLong()
            SHORT_TYPE_BOXED    -> (value.unwrap() as Short).toLong()
            CHAR_TYPE_BOXED     -> (value.unwrap() as Char).toLong()
            BOOLEAN_TYPE_BOXED  -> (value.unwrap() as Boolean).toInt().toLong()
            else                -> get(value)?.id ?: NULL_OBJECT_ID
        }
    }
}

fun ObjectRegistry.getOrRegisterValueID(type: Type, value: OpaqueValue?): ValueID {
    if (value == null) return NULL_OBJECT_ID
    return when (type.sort) {
        Type.LONG       -> (value.unwrap() as Long)
        Type.INT        -> (value.unwrap() as Int).toLong()
        Type.SHORT      -> (value.unwrap() as Short).toLong()
        Type.CHAR       -> (value.unwrap() as Char).toLong()

        // sometimes, due to JVM internals, boolean values can be reinterpreted as byte values
        // (e.g., because of BALOAD and BASTORE instructions are used for both boolean and byte arrays);
        // thus if the type-cast failed, we try to reinterpret the value and cast it to manually
        Type.BYTE       ->
            (value.unwrap() as? Byte)?.toLong() ?:
            (value.unwrap() as Boolean).toInt().toLong()
        Type.BOOLEAN    ->
            (value.unwrap() as? Boolean)?.toInt()?.toLong() ?:
            (value.unwrap() as Byte).toBoolean().toInt().toLong()

        else            -> when (type) {
            LONG_TYPE_BOXED     -> (value.unwrap() as Long)
            INT_TYPE_BOXED      -> (value.unwrap() as Int).toLong()
            BYTE_TYPE_BOXED     -> (value.unwrap() as Byte).toLong()
            SHORT_TYPE_BOXED    -> (value.unwrap() as Short).toLong()
            CHAR_TYPE_BOXED     -> (value.unwrap() as Char).toLong()
            BOOLEAN_TYPE_BOXED  -> (value.unwrap() as Boolean).toInt().toLong()
            else                -> getOrRegisterObjectID(value)
        }
    }
}