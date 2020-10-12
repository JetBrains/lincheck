/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import java.util.*

/**
 * Object manager holds information about some object characteristics.
 * Tracks the creation of local objects and leaks of their references to non-local objects.
 */
internal class ObjectManager {
    // For each local object store all objects that depend on it (e.g, are referenced by it).
    // Non-local objects are not present in the map.
    private val localObjects = IdentityHashMap<Any, MutableList<Any>>()
    // Some objects can have a name associated with them
    private val objectNames = IdentityHashMap<Any, String>()

    fun newLocalObject(o: Any) {
        localObjects[o] = mutableListOf()
    }

    fun deleteLocalObject(o: Any?) {
        if (o == null) return
        val objects = localObjects.remove(o) ?: return
        // when an object stops being local, all dependent objects stop as well
        objects.forEach { deleteLocalObject(it) }
    }

    fun isLocalObject(o: Any?) = localObjects.containsKey(o)

    /**
     * Adds a new "has reference to" dependency.
     * A [dependent] is either stored in a field of an [owner] or is an element in an [owner]'s array.
     */
    fun addDependency(owner: Any, dependent: Any?) {
        if (dependent == null) return
        val ownerObjects = localObjects[owner]
        if (ownerObjects != null) {
            // actually save the dependency
            ownerObjects.add(dependent)
        } else {
            // a link to the dependent leaked to a non-local object
            deleteLocalObject(dependent)
        }
    }

    fun registerObjectName(o: Any, name: String) {
        objectNames[o] = name
    }

    fun getObjectName(o: Any) = objectNames[o]
}
