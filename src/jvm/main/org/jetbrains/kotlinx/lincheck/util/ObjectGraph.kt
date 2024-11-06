/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import org.jetbrains.kotlinx.lincheck.*
import java.util.concurrent.atomic.*
import java.lang.reflect.*
import java.util.*


/**
 * Traverses a subgraph of objects reachable from a given root object in BFS order.
 *
 * For a given reachable (non-array) object,
 * all its fields are traversed and [onField] callback is called on each field value.
 *
 * In case if a reachable object is java array, atomic array, or atomicfu array,
 * the elements of the array are traversed and [onArrayElement] callback is called on each array element.
 *
 * Both [onField] and [onArrayElement] callbacks should return the next object to be traversed,
 * or null if further traversal is not required for given field or array element.
 * In general, the returned object should be a field value or an array element itself;
 * however, the callbacks can implement object skipping by returning some other reachable object.
 *
 * Some objects are skipped and are not traversed,
 * for instance, primitive boxed objects, immutable objects (e.g., String), and some others.
 *
 * @param root object to start the traversal from.
 * @param onField callback for fields traversal, accepts `(fieldOwner, field, fieldValue)`.
 *   Returns an object to be traversed next.
 * @param onArrayElement callback for array elements traversal, accepts `(array, index, elementValue)`.
 *   Returns an object to be traversed next.
 */
internal inline fun traverseObjectGraph(
    root: Any,
    onField: (obj: Any, field: Field, value: Any?) -> Any?,
    onArrayElement: (array: Any, index: Int, element: Any?) -> Any?,
) {
    val queue = ArrayDeque<Any>()
    val visitedObjects = Collections.newSetFromMap<Any>(IdentityHashMap())
    val isImmutable = { obj: Any? ->
        (
            obj == null ||
            obj is String ||
            isAtomicFieldUpdater(obj) ||
            isUnsafeClass(obj.javaClass.name)
        )
    }
    val shouldTraverse = { obj: Any? ->
        (
            obj != null &&
            !obj.javaClass.isPrimitive && // no primitives traversing
            !obj.isPrimitiveWrapper && // no primitive wrappers traversing
            !isImmutable(obj) && // no immutable objects traversing
            obj !in visitedObjects // no reference-cycles allowed during traversing
        )
    }

    if (!shouldTraverse(root)) return

    queue.add(root)
    visitedObjects.add(root)

    val processNextObject: (nextObject: Any?) -> Unit = { nextObject ->
        if (
            nextObject != null && // user determines what to append to queue
            !nextObject.javaClass.isPrimitive && // no primitives traversing
            !nextObject.isPrimitiveWrapper && // no primitive wrappers traversing
            !isImmutable(nextObject) && // no immutable objects traversing
            nextObject !in visitedObjects // no reference-cycles allowed during traversing
        ) {
            queue.add(nextObject)
            visitedObjects.add(nextObject)
        }
    }

    while (queue.isNotEmpty()) {
        val currentObj = queue.removeFirst()

        when {
            currentObj is Class<*> -> {}
            currentObj.javaClass.isArray || isAtomicArray(currentObj) -> {
                traverseArrayElements(currentObj) { _ /* currentObj */, index, elementValue ->
                    processNextObject(onArrayElement(currentObj, index, elementValue))
                }
            }
            else -> {
                traverseObjectFields(currentObj) { _ /* currentObj */, field, fieldValue ->
                    processNextObject(onField(currentObj, field, fieldValue))
                }
            }
        }
    }
}

/**
 * Traverses [obj] elements if it is a pure array, java atomic array, or atomicfu array, otherwise no-op.
 *
 * @param obj array which elements to traverse.
 * @param onArrayElement callback which accepts `(obj, index, elementValue)`.
 */
internal inline fun traverseArrayElements(obj: Any, onArrayElement: (array: Any, index: Int, element: Any?) -> Unit) {
    require(obj.javaClass.isArray || isAtomicArray(obj))

    val length = getArrayLength(obj)
    // TODO: casting `obj` to atomicfu class and accessing its field directly causes compilation error,
    //  see https://youtrack.jetbrains.com/issue/KT-49792 and https://youtrack.jetbrains.com/issue/KT-47749
    val cachedAtomicFUGetMethod: Method? = if (isAtomicFUArray(obj)) obj.javaClass.getMethod("get", Int::class.java) else null

    for (index in 0..length - 1) {
        val result = runCatching {
            if (isAtomicFUArray(obj)) {
                cachedAtomicFUGetMethod!!.invoke(obj, index) as Int
            }
            else when (obj) {
                is AtomicReferenceArray<*> -> obj.get(index)
                is AtomicIntegerArray -> obj.get(index)
                is AtomicLongArray -> obj.get(index)
                else -> readArrayElementViaUnsafe(obj, index)
            }
        }

        if (result.isSuccess) {
            onArrayElement(obj, index, result.getOrNull())
        }
    }
}

/**
 * Traverses [obj] fields (including fields from superclasses).
 *
 * @param obj array which elements to traverse.
 * @param onField callback which accepts `(obj, field, fieldValue)`.
 */
internal inline fun traverseObjectFields(obj: Any, onField: (obj: Any, field: Field, value: Any?) -> Unit) {
    obj.javaClass.allDeclaredFieldWithSuperclasses.forEach { field ->
        // We wrap an unsafe read into `runCatching` to handle `UnsupportedOperationException`,
        // which can be thrown, for instance, when attempting to read a field of
        // a hidden class (starting from Java 15).
        val result = runCatching { readFieldViaUnsafe(obj, field) }

        // do not pass fields to the user that are non-readable by Unsafe
        if (result.isSuccess) {
            val fieldValue = result.getOrNull()
            onField(obj, field, fieldValue)
        }
    }
}

/**
 * Returns all found fields in the hierarchy.
 * Multiple fields with the same name and the same type may be returned
 * if they appear in the subclass and a parent class.
 */
internal val Class<*>.allDeclaredFieldWithSuperclasses get(): List<Field> {
    val fields: MutableList<Field> = ArrayList<Field>()
    var currentClass: Class<*>? = this
    while (currentClass != null) {
        val declaredFields: Array<Field> = currentClass.declaredFields
        fields.addAll(declaredFields)
        currentClass = currentClass.superclass
    }
    return fields
}

internal fun getArrayElementOffset(arr: Any, index: Int): Long {
    val clazz = arr::class.java
    val baseOffset = UnsafeHolder.UNSAFE.arrayBaseOffset(clazz).toLong()
    val indexScale = UnsafeHolder.UNSAFE.arrayIndexScale(clazz).toLong()

    return baseOffset + index * indexScale
}

internal fun getArrayLength(arr: Any): Int {
    return when {
        arr is Array<*>     -> arr.size
        arr is IntArray     -> arr.size
        arr is DoubleArray  -> arr.size
        arr is FloatArray   -> arr.size
        arr is LongArray    -> arr.size
        arr is ShortArray   -> arr.size
        arr is ByteArray    -> arr.size
        arr is BooleanArray -> arr.size
        arr is CharArray    -> arr.size
        isAtomicArray(arr)  -> getAtomicArrayLength(arr)
        else -> error("Argument is not an array")
    }
}

internal fun getAtomicArrayLength(arr: Any): Int {
    return when {
        arr is AtomicReferenceArray<*> -> arr.length()
        arr is AtomicIntegerArray -> arr.length()
        arr is AtomicLongArray -> arr.length()
        isAtomicFUArray(arr) -> arr.javaClass.getMethod("getSize").invoke(arr) as Int
        else -> error("Argument is not atomic array")
    }
}
