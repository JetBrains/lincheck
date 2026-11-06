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
 * Returns final instance fields declared by this class, excluding inherited and static fields.
 *
 * These are exactly the final fields that only the constructors (and instance initializers) of this class may assign,
 * so the result describes the state a constructor of this class is responsible for initializing.
 * Final fields of superclasses belong to the superclasses' constructors
 * and are returned by their own `declaredFinalInstanceFields`.
 *
 * Unlike `allDeclared*Fields`, only one class is scanned, so each field appears exactly once.
 * Ф superclass field with the same name is hidden, not replaced,
 * and remains a separate memory location of the object; it is not included in the result.
 */
val Class<*>.declaredFinalInstanceFields get(): List<Field> =
    ClassReflectionCache[this].declaredFinalInstanceFields

/**
 * Resolves [className] against [clazz] and its superclass chain, choosing the nearest match.
 *
 * With no name, returns [clazz]; with no class, loads and initializes the fully qualified name.
 * Otherwise, searches the class hierarchy for a class with the given name.
 * Only super-classes are considered, interfaces are not searched.
 *
 * Names match a suffix of the binary or canonical class name.
 *
 * @throws IllegalStateException if both arguments are null or no class in the hierarchy matches.
 * @throws ClassNotFoundException if loading by name fails.
 */
fun resolveClass(clazz: Class<*>? = null, className: String? = null): Class<*> {
    if (className == null) {
        check(clazz != null)
        return clazz
    }
    if (clazz == null) {
        return ClassCache.forName(className)
    }
    if (matchClassName(clazz, className)) {
        return clazz
    }
    return ClassReflectionCache[clazz].resolvedClasses.getOrPut(className) {
        var superClass = clazz.superclass
        while (superClass != null) {
            if (matchClassName(superClass, className)) return@getOrPut superClass
            superClass = superClass.superclass
        }
        throw IllegalStateException("Cannot find class $className for object of class ${clazz.name}!")
    }
}

/**
 * Resolves a field by name, searching each class from [clazz] up its superclass chain.
 *
 * Resolved field is the field accessed by a field instruction (`getfield`, `putfield`, `getstatic`, `putstatic`)
 * whose symbolic owner class is [clazz] and whose field name is [fieldName].
 * The accessed field is determined by the instruction's owner class,
 * not by the runtime class of the accessed object.
 * Hence, [clazz] must be the instruction's owner class (or the class resolved from it):
 * if the object's runtime class declares a same-named field, that field hides the resolved one,
 * yet the instruction still accesses the resolved field.
 *
 * During resolve, declared fields of any visibility precede inherited fields, including hidden public fields.
 * If the superclass chain has no match, searches public fields, including inherited interface fields.
 *
 * This resolution procedure mirrors the JVM field resolution (JVMS §5.4.3.2):
 * the nearest field declared by [clazz] or its superclasses wins, regardless of visibility;
 * public interface fields are considered only if the superclass chain has no match.
 * The JVM instead checks the superinterfaces of each class before its superclass;
 * the two orders differ only if an interface field and a superclass field share the name,
 * which `javac` rejects as an ambiguous reference.
 *
 * @throws IllegalStateException if no field matches [fieldName].
 */
fun resolveField(clazz: Class<*>, className: String, fieldName: String): Field {
    return ClassReflectionCache[clazz].resolvedFields[fieldName]
        ?: throw IllegalStateException("Cannot find field $className::$fieldName for class $clazz!")
}

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
        val declaredFinalInstanceFields: List<Field>,
        val companionClass: Class<*>?,
        val resolvedFields: Map<String, Field>,
        val resolvedClasses: ConcurrentHashMap<String, Class<*>> = ConcurrentHashMap(),
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
                declaredFinalInstanceFields = instanceFields.filter {
                    it.declaringClass == type && Modifier.isFinal(it.modifiers)
                },
                companionClass = companionClass,
                resolvedFields = computeFieldsByName(type),
            )
        }
    }

    private fun computeFieldsByName(clazz: Class<*>): Map<String, Field> {
        val fields = mutableMapOf<String, Field>()
        var currentClass: Class<*>? = clazz
        while (currentClass != null) {
            for (field in currentClass.declaredFields) fields.putIfAbsent(field.name, field)
            currentClass = currentClass.superclass
        }
        for (field in clazz.fields) fields.putIfAbsent(field.name, field)
        return fields
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

private fun matchClassName(clazz: Class<*>, className: String) =
    clazz.name.endsWith(className) || (clazz.canonicalName?.endsWith(className) ?: false)
