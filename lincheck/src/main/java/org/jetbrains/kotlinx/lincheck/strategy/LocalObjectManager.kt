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

class LocalObjectManager {
    // for each local object store number of objects that depend on it
    private val localObjects = IdentityHashMap<Any, MutableList<Any>>()

    fun newLocalObject(o: Any) {
        localObjects[o] = mutableListOf()
    }

    fun deleteLocalObject(o: Any?) {
        if (o == null) return

        val objects = localObjects[o] ?: return
        localObjects.remove(o)

        // when an object stops being local, all dependent objects stop too
        for (obj in objects) {
            deleteLocalObject(obj)
        }
    }

    fun isLocalObject(o: Any?) = localObjects.containsKey(o)

    fun addDependency(owner: Any, dependant: Any?) {
        if (dependant == null) return

        val ownerObjects = localObjects[owner]

        if (ownerObjects != null) {
            ownerObjects.add(dependant)
        } else {
            // a link to dependant leaked to a non local object
            deleteLocalObject(dependant)
        }
    }
}