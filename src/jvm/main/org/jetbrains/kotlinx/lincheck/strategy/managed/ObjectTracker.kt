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

abstract class ObjectTracker {

    abstract fun registerObject(iThread: Int, obj: OpaqueValue): ObjectID

    abstract fun getOrRegisterObjectID(obj: OpaqueValue): ObjectID

    abstract fun getObjectID(obj: OpaqueValue): ObjectID

    abstract fun getObject(id: ObjectID): OpaqueValue?

    abstract fun reset()

}

fun ObjectTracker.getValue(type: Type, id: ValueID): OpaqueValue? = when (type.sort) {
    Type.LONG       -> id.opaque()
    Type.INT        -> id.toInt().opaque()
    Type.BYTE       -> id.toByte().opaque()
    Type.SHORT      -> id.toShort().opaque()
    Type.CHAR       -> id.toChar().opaque()
    Type.BOOLEAN    -> id.toInt().toBoolean().opaque()
    else            -> getObject(id)
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
        else            -> getObjectID(value)
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
        else            -> getOrRegisterObjectID(value)
    }
}

fun ObjectTracker.getOrRegisterObjectID(obj: OpaqueValue?): ObjectID =
    if (obj == null) NULL_OBJECT_ID else getOrRegisterObjectID(obj)


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