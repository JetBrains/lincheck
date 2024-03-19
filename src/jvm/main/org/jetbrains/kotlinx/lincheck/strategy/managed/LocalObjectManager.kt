/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import java.util.*


/**
 * Manages objects created within the local scope.
 * The purpose of this manager is to keep track of locally created objects that aren't yet shared,
 * and automatically delete their dependencies when they become shared.
 * This tracking helps to avoid unnecessary interleavings, which can occur if access to such local
 * objects triggers switch points in the model checking strategy.
 */
internal class LocalObjectManager {
    /**
     * An identity hash map holding each local object and its dependent objects.
     * Each local object is a key, and its value is a list of objects accessible from it.
     * Note that non-local objects are excluded from this map.
     */
    private val localObjects = IdentityHashMap<Any, MutableList<Any>>()

    /**
     * Registers a new object as a locally accessible one.
     */
    fun registerNewObject(obj: Any) {
        localObjects[obj] = mutableListOf()
    }

    /**
     * Removes the specified local object and its dependencies from the set of local objects.
     * If the removing object references other local objects, they are also removed recursively.
     */
    fun markObjectNonLocal(obj: Any?) {
        if (obj == null) return
        val objects = localObjects.remove(obj) ?: return
        objects.forEach { markObjectNonLocal(it) }
    }

    /**
     * Checks if an object is only locally accessible.
     */
    fun isLocalObject(obj: Any?) = localObjects.containsKey(obj)

    /**
     * Adds a dependency between the owner object and the dependent object.
     * A dependent object is an object that is either stored in a field of the owner
     * or is an element in the owner's array.
     * If the owner isn't in the local objects map, the method will delete the dependent
     * from the local objects map, making it shared.
     *
     * @param obj The owner object.
     * @param fieldOrArrayCellValue The object that depends on the owner.
     */
    fun onWriteToObjectFieldOrArrayCell(obj: Any, fieldOrArrayCellValue: Any?) {
        if (fieldOrArrayCellValue == null) return
        val ownerObjects = localObjects[obj]
        if (ownerObjects != null) {
            ownerObjects.add(fieldOrArrayCellValue)
        } else {
            markObjectNonLocal(fieldOrArrayCellValue)
        }
    }
}