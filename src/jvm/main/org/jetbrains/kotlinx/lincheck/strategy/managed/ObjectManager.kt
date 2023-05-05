/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import java.util.*

/**
 * First of all, the object manager keeps information of locally accessible objects
 * (e.g., locally created but not shared objects yet) by tracking their creations
 * and leaks for avoiding useless interleavings - accessing such objects should not
 * create switch points in managed strategies.
 *
 * Secondly, this manager keeps names for some objects, and these names are used
 * in trace representations. For example, `j.u.c.atomic.AtomicXXX` objects are
 * associated with the corresponding field names, so that in the trace users see
 * the field names instead of something like `AtomicInteger@100500`.
 */
internal class ObjectManager(private val testClass: Class<out Any>) {
    // For each local object store all objects that depend on it (e.g, are referenced by it).
    // Non-local objects are not presented in this map.
    private val localObjects = IdentityHashMap<Any, MutableList<Any>>()
    // Stores object names (typically, the corresponding field names where they are uniquely stored).
    private val objectNames = IdentityHashMap<Any, String>()

    fun newLocalObject(o: Any) {
        // a test instance can not be local object.
        // check by name to ignore difference in loaders
        if (o::class.java.name == testClass.name) return
        // add o to list of local object with no dependencies
        localObjects[o] = mutableListOf()
    }

    fun deleteLocalObject(o: Any?) {
        if (o == null) return
        val objects = localObjects.remove(o) ?: return
        // When an object becomes shared, all dependent objects
        // should be deleted from the local ones as well
        objects.forEach { deleteLocalObject(it) }
    }

    fun isLocalObject(o: Any?) = localObjects.containsKey(o)

    /**
     * Adds a new "has reference to" dependency.
     * A [dependent] is either stored in a field of [owner] or is an element in [owner]'s array.
     */
    fun addDependency(owner: Any, dependent: Any?) {
        if (dependent == null) return
        val ownerObjects = localObjects[owner]
        if (ownerObjects != null) {
            // Save the dependency
            ownerObjects.add(dependent)
        } else {
            // A link to the dependent references a non-local object
            deleteLocalObject(dependent)
        }
    }

    fun setObjectName(o: Any, name: String) {
        objectNames[o] = name
    }

    fun getObjectName(o: Any) = objectNames[o]
}
