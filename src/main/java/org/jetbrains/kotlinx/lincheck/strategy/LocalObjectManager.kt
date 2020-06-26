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
package org.jetbrains.kotlinx.lincheck.strategy

import java.util.*

/**
 * Tracks creations of local objects and leaks of their references to non-local objects.
 */
class LocalObjectManager {
    // for each local object store all objects that depend on it (e.g, are referenced by it)
    private val localObjects = IdentityHashMap<Any, MutableList<Any>>()

    fun newLocalObject(o: Any) {
        localObjects[o] = mutableListOf()
    }

    fun deleteLocalObject(o: Any?) {
        if (o == null) return
        val objects = localObjects.remove(o) ?: return
        // when an object stops being local, all dependent objects stop too
        objects.forEach { deleteLocalObject(it) }
    }

    fun isLocalObject(o: Any?) = localObjects.containsKey(o)

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
}
