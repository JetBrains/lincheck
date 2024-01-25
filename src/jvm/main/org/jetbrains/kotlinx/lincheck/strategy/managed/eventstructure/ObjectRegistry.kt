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
import org.jetbrains.kotlinx.lincheck.utils.*
import java.util.*

data class ObjectEntry(
    val id: ObjectID,
    val obj: OpaqueValue,
    val allocation: AtomicThreadEvent,
) {
    init {
        require(id != NULL_OBJECT_ID)
        require(allocation.label is InitializationLabel || allocation.label is ObjectAllocationLabel)
    }

    val isExternal: Boolean
        get() = (allocation.label is InitializationLabel)

    // TODO: remove?
    var isLocal: Boolean =
        (id != STATIC_OBJECT_ID)

    val localThreadID: ThreadID
        get() = if (isLocal) allocation.threadId else -1
}

// TODO: use in EventStructure !!!
class ObjectRegistry {

    private val objectIdIndex = HashMap<ObjectID, ObjectEntry>()
    private val objectIndex = IdentityHashMap<Any, ObjectEntry>()

    fun register(entry: ObjectEntry) {
        objectIdIndex.put(entry.id, entry).ensureNull()
        objectIndex.put(entry.obj.unwrap(), entry).ensureNull()
    }

    operator fun get(id: ObjectID): ObjectEntry? =
        objectIdIndex[id]

    operator fun get(obj: Any): ObjectEntry? =
        objectIndex[obj]

    fun retain(predicate: (ObjectEntry) -> Boolean) {
        objectIdIndex.values.retainAll(predicate)
        objectIndex.values.retainAll(predicate)
    }

}