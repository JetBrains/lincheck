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

    val shouldTraverse = { obj: Any? ->
        obj != null &&
        !obj.isImmutable && // no immutable objects traversing
        !isAtomicFieldUpdater(obj) && // no afu traversing
        !isUnsafeClass(obj.javaClass.name) && // no unsafe traversing
        obj !is Class<*> && // no class objects traversing
        obj !in visitedObjects // no reference-cycles allowed during traversing
    }

    if (!shouldTraverse(root)) return

    queue.add(root)
    visitedObjects.add(root)

    val processNextObject: (nextObject: Any?) -> Unit = { nextObject ->
        if (shouldTraverse(nextObject)) {
            queue.add(nextObject!!)
            visitedObjects.add(nextObject)
        }
    }

    while (queue.isNotEmpty()) {
        val currentObj = queue.removeFirst()

        when {
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
 * Traverses a subgraph of objects reachable from a given root object in BFS order,
 * and applies a callback on each visited object.
 *
 * @param root the starting object for the traversal.
 * @param onObject callback that is invoked for each object in the graph.
 *
 * @see traverseObjectGraph
 */
internal inline fun traverseObjectGraph(root: Any, onObject: (obj: Any) -> Unit) {
    onObject(root)
    traverseObjectGraph(root,
        onField = { _, _, fieldValue -> fieldValue?.also { onObject(it) } },
        onArrayElement = { _, _, arrayElement -> arrayElement?.also { onObject(it) } }
    )
}

/**
 * Traverses [array] elements if it is a pure array, java atomic array, or atomicfu array, otherwise no-op.
 *
 * @param array array which elements to traverse.
 * @param onArrayElement callback which accepts `(obj, index, elementValue)`.
 */
internal inline fun traverseArrayElements(array: Any, onArrayElement: (array: Any, index: Int, element: Any?) -> Unit) {
    require(array.javaClass.isArray || isAtomicArray(array))

    val length = getArrayLength(array)
    // TODO: casting `obj` to atomicfu class and accessing its field directly causes compilation error,
    //  see https://youtrack.jetbrains.com/issue/KT-49792 and https://youtrack.jetbrains.com/issue/KT-47749
    val cachedAtomicFUGetMethod: Method? = if (isAtomicFUArray(array)) array.javaClass.getMethod("get", Int::class.java) else null

    for (index in 0 ..< length) {
        val result = runCatching {
            if (isAtomicFUArray(array)) {
                cachedAtomicFUGetMethod!!.invoke(array, index) as Int
            }
            else when (array) {
                is AtomicReferenceArray<*> -> array.get(index)
                is AtomicIntegerArray -> array.get(index)
                is AtomicLongArray -> array.get(index)
                else -> readArrayElementViaUnsafe(array, index)
            }
        }

        if (result.isSuccess) {
            onArrayElement(array, index, result.getOrNull())
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
        // we wrap an unsafe read into `runCatching` to handle `UnsupportedOperationException`,
        // which can be thrown, for instance, when attempting to read
        // a field of a hidden or record class (starting from Java 15);
        // in this case we fall back to read via reflection
        val result = runCatching { readFieldViaUnsafe(obj, field) }
            .recoverCatching { field.apply { isAccessible = true }.get(obj) }
        // do not pass non-readable fields
        if (result.isSuccess) {
            val fieldValue = result.getOrNull()
            onField(obj, field, fieldValue)
        }
    }
}

internal val Any.isImmutable get() =
    this.isPrimitiveWrapper ||
    this is String

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
