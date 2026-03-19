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
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides a thread-safe caching mechanism for [Class] lookup by its fully qualified class name.
 */
object ClassCache {
    // We store the class references in a local map to avoid repeated Class.forName calls and reflection overhead
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
    ClassReflectionCache[this].companionClass

/**
 * Returns all declared fields in the class, including fields from superclasses and interfaces.
 *
 * Multiple fields with the same name and the same type may be returned
 * if they appear in the subclass and a parent class.
 */
val Class<*>.allDeclaredFields get(): List<Field> =
    ClassReflectionCache[this].allDeclaredFields

/**
 * Returns all declared instance fields in the class, including fields from superclasses and interfaces.
 *
 * Multiple fields with the same name and the same type may be returned
 * if they appear in the subclass and a parent class.
 */
val Class<*>.allDeclaredInstanceFields get(): List<Field> =
    ClassReflectionCache[this].allDeclaredInstanceFields

/**
 * Returns all declared static fields in the class, including fields from superclasses and interfaces.
 *
 * Multiple fields with the same name and the same type may be returned
 * if they appear in the subclass and a parent class.
 */
val Class<*>.allDeclaredStaticFields get(): List<Field> =
    ClassReflectionCache[this].allDeclaredStaticFields

/**
 * Finds the field name of [this] object that directly references the given object [obj].
 *
 * @param this the object which fields are look-up.
 * @param obj the target object to search for in instance fields of [this].
 * @return the name of the field that references the given object, or null if no such field is found.
 */
fun Any.findInstanceFieldReferringTo(obj: Any): Field? {
    for (field in this.javaClass.allDeclaredFields) {
        if (readFieldSafely(this, field).getOrNull() === obj) {
            return field
        }
    }
    return null
}

/**
 * A utility object for caching class reflection metadata in a thread-safe and gc-friendly manner.
 *
 * The implementation relies on a [ClassValue] to lazily compute the cached values.
 */
private object ClassReflectionCache {
    data class ReflectionData(
        val allDeclaredFields: List<Field>,
        val allDeclaredInstanceFields: List<Field>,
        val allDeclaredStaticFields: List<Field>,
        val companionClass: Class<*>?,
    )

    private val cache = object : ClassValue<ReflectionData>() {
        override fun computeValue(type: Class<*>): ReflectionData {
            val allDeclaredFields = computeAllDeclaredFields(type)
            val companionClass = allDeclaredFields.firstOrNull { it.name == "Companion" }?.type
            val (staticFields, instanceFields) = allDeclaredFields.partition { Modifier.isStatic(it.modifiers) }

            return ReflectionData(
                allDeclaredFields = allDeclaredFields,
                allDeclaredInstanceFields = instanceFields,
                allDeclaredStaticFields = staticFields,
                companionClass = companionClass,
            )
        }
    }

    private fun computeAllDeclaredFields(clazz: Class<*>): List<Field> {
        if (clazz.superclass == null && clazz.interfaces.isEmpty()) {
            return clazz.declaredFields.asList()
        }

        val fields: MutableList<Field> = mutableListOf()
        val queue: MutableList<Class<*>> = mutableListOf(clazz)
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

    operator fun get(clazz: Class<*>): ReflectionData = cache.get(clazz)
}