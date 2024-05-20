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
import org.jetbrains.kotlinx.lincheck.util.*
import kotlin.collections.HashMap
import java.util.*

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

    fun registerExternalObject(obj: OpaqueValue, allocation: AtomicThreadEvent): ObjectID {
        check(allocation.label is InitializationLabel)
        if (obj.isPrimitive) {
            val entry = primitiveIndex.computeIfAbsent(obj.unwrap()) {
                val id = ++objectCounter
                val entry = ObjectEntry(id, obj, allocation)
                objectIdIndex.put(entry.id, entry).ensureNull()
                return@computeIfAbsent entry
            }
            return entry.id
        }
        val id = nextObjectID
        val entry = ObjectEntry(id, obj, allocation)
        register(entry)
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