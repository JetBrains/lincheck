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