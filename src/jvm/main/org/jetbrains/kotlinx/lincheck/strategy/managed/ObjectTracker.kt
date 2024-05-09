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

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.utils.*
import org.objectweb.asm.Type
import java.util.*
import kotlin.reflect.KClass

abstract class ObjectTracker {

    abstract fun registerObject(iThread: Int, obj: OpaqueValue): ObjectID

    abstract fun getOrRegisterObjectID(obj: OpaqueValue): ObjectID

    abstract fun getObjectID(obj: OpaqueValue): ObjectID

    abstract fun getObject(id: ObjectID): OpaqueValue?

    abstract fun reset()

}

fun ObjectTracker.getOrRegisterObjectID(obj: OpaqueValue?): ObjectID =
    if (obj == null) NULL_OBJECT_ID else getOrRegisterObjectID(obj)

fun ObjectTracker.getValue(type: Type, id: ValueID): OpaqueValue? = when (type.sort) {
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
        else                -> getObject(id)
    }
}

fun ObjectTracker.getValueID(type: Type, value: OpaqueValue?): ValueID {
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
            else                -> getObjectID(value)
        }
    }
}

fun ObjectTracker.getOrRegisterValueID(type: Type, value: OpaqueValue?): ValueID {
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
            else                -> getOrRegisterObjectID(value)
        }
    }
}

internal fun Type.getKClass(): KClass<*> = when (sort) {
    Type.INT     -> Int::class
    Type.BYTE    -> Byte::class
    Type.SHORT   -> Short::class
    Type.LONG    -> Long::class
    Type.FLOAT   -> Float::class
    Type.DOUBLE  -> Double::class
    Type.CHAR    -> Char::class
    Type.BOOLEAN -> Boolean::class
    Type.OBJECT  -> when (this) {
        INT_TYPE_BOXED      -> Int::class
        BYTE_TYPE_BOXED     -> Byte::class
        SHORT_TYPE_BOXED    -> Short::class
        LONG_TYPE_BOXED     -> Long::class
        CHAR_TYPE_BOXED     -> Char::class
        BOOLEAN_TYPE_BOXED  -> Boolean::class
        else                -> Any::class
    }
    Type.ARRAY   -> when (elementType.sort) {
        Type.INT     -> IntArray::class
        Type.BYTE    -> ByteArray::class
        Type.SHORT   -> ShortArray::class
        Type.LONG    -> LongArray::class
        Type.FLOAT   -> FloatArray::class
        Type.DOUBLE  -> DoubleArray::class
        Type.CHAR    -> CharArray::class
        Type.BOOLEAN -> BooleanArray::class
        else         -> Array::class
    }
    else -> throw IllegalArgumentException()
}

private val INT_TYPE_BOXED      = Type.getType("Ljava/lang/Integer")
private val LONG_TYPE_BOXED     = Type.getType("Ljava/lang/Long")
private val SHORT_TYPE_BOXED    = Type.getType("Ljava/lang/Short")
private val BYTE_TYPE_BOXED     = Type.getType("Ljava/lang/Byte")
private val CHAR_TYPE_BOXED     = Type.getType("Ljava/lang/Character")
private val BOOLEAN_TYPE_BOXED  = Type.getType("Ljava/lang/Boolean")


internal class PlainObjectTracker : ObjectTracker() {

    private var nextObjectID = 1 + NULL_OBJECT_ID

    private val objectIdIndex = HashMap<ObjectID, OpaqueValue>()
    private val objectIndex = IdentityHashMap<Any, ObjectID>()

    private fun registerObject(id: ObjectID, value: OpaqueValue) {
        objectIdIndex.put(id, value).ensureNull()
        objectIndex.put(value.unwrap(), id).ensureNull()
    }

    override fun registerObject(iThread: Int, obj: OpaqueValue): ObjectID {
        val id = nextObjectID++
        registerObject(id, obj)
        return id
    }

    override fun getOrRegisterObjectID(obj: OpaqueValue): ObjectID {
        objectIndex[obj.unwrap()]?.let { return it }
        val id = nextObjectID++
        registerObject(id, obj)
        return id
    }

    override fun getObject(id: ObjectID): OpaqueValue? =
        objectIdIndex[id]

    override fun getObjectID(obj: OpaqueValue): ObjectID =
        objectIndex[obj.unwrap()] ?: INVALID_OBJECT_ID

    override fun reset() {
        objectIdIndex.clear()
        objectIndex.clear()
    }

}