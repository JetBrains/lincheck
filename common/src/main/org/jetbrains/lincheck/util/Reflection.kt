/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap


// We store the class references in a local map to avoid repeated Class.forName calls and reflection overhead

/**
 * Provides a thread-safe caching mechanism for [Class] lookup by its fully qualified class name.
 */
object ClassCache {
    private val cache = ConcurrentHashMap<String, Class<*>>()

    /**
     * Retrieves the [Class] object associated with the given fully qualified class name.
     * The method caches the result to avoid redundant lookups for the same class name.
     *
     * @param className the fully qualified name of the desired class
     * @return the [Class] object representing the desired class
     * @throws ClassNotFoundException if the class cannot be located by its name
     */
    fun forName(className: String): Class<*> =
        cache.getOrPut(className) { Class.forName(className) }
}

/**
 * Returns the companion object's class of this class if it exists.
 */
val Class<*>.companionClass: Class<*>? get() =
    declaredFields.firstOrNull { it.name == "Companion" }?.type

/**
 * Returns all found fields in the hierarchy.
 * Multiple fields with the same name and the same type may be returned
 * if they appear in the subclass and a parent class.
 */
val Class<*>.allDeclaredFieldWithSuperclasses get(): List<Field> {
    if (superclass == null && interfaces.isEmpty()) {
        return this.declaredFields.asList()
    }

    val fields: MutableList<Field> = mutableListOf()
    val queue: MutableList<Class<*>> = mutableListOf(this)
    while (queue.isNotEmpty()) {
        val currentClass = queue.removeLast()
        fields.addAll(currentClass.declaredFields)

        queue.addAll(currentClass.interfaces)
        if (currentClass.superclass != null) {
            queue.add(currentClass.superclass)
        }
    }
    return fields
}

/**
 * Finds the field name of [this] object that directly references the given object [obj].
 *
 * @param this the object which fields are look-up.
 * @param obj the target object to search for in instance fields of [this].
 * @return the name of the field that references the given object, or null if no such field is found.
 */
fun Any.findInstanceFieldReferringTo(obj: Any): Field? {
    for (field in this.javaClass.allDeclaredFieldWithSuperclasses) {
        if (readFieldSafely(this, field).getOrNull() === obj) {
            return field
        }
    }
    return null
}