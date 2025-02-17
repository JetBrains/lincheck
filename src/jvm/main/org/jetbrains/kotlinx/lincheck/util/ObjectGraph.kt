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

import java.util.concurrent.atomic.*
import java.lang.reflect.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*


private typealias FieldCallback = (obj: Any, field: Field, value: Any?) -> Any?
private typealias ArrayElementCallback = (array: Any, index: Int, element: Any?) -> Any?

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
 * @param traverseStaticFields if true, then all static fields are also traversed,
 *   otherwise only non-static fields are traversed.
 * @param onField callback for fields traversal, accepts `(fieldOwner, field, fieldValue)`.
 *   Returns an object to be traversed next.
 * @param onArrayElement callback for array elements traversal, accepts `(array, index, elementValue)`.
 *   Returns an object to be traversed next.
 */
internal fun traverseObjectGraph(
    root: Any,
    traverseStaticFields: Boolean = false,
    onField: FieldCallback?,
    onArrayElement: ArrayElementCallback?,
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
            onArrayElement != null &&
            (currentObj.javaClass.isArray || isAtomicArray(currentObj)) -> {
                traverseArrayElements(currentObj) { _ /* currentObj */, index, elementValue ->
                    processNextObject(onArrayElement(currentObj, index, elementValue))
                }
            }
            onField != null -> {
                traverseObjectFields(currentObj,
                    traverseStaticFields = traverseStaticFields
                ) { _ /* currentObj */, field, fieldValue ->
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
 * @param traverseStaticFields if true, then all static fields are also traversed,
 *   otherwise only non-static fields are traversed.
 * @param onObject callback that is invoked for each object in the graph.
 *   Should return the next object to traverse, may return null to prune further traversal.
 *
 * @see traverseObjectGraph
 */
internal fun traverseObjectGraph(
    root: Any,
    traverseStaticFields: Boolean = false,
    onObject: (obj: Any) -> Any?
) {
    val obj = onObject(root) ?: return
    traverseObjectGraph(obj,
        onField = { _, _, fieldValue -> fieldValue?.let(onObject) },
        onArrayElement = { _, _, arrayElement -> arrayElement?.let(onObject) },
        traverseStaticFields = traverseStaticFields,
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
 * @param traverseStaticFields if true, then all static fields are also traversed,
 *   otherwise only non-static fields are traversed.
 * @param onField callback which accepts `(obj, field, fieldValue)`.
 */
internal inline fun traverseObjectFields(
    obj: Any,
    traverseStaticFields: Boolean = false,
    onField: (obj: Any, field: Field, value: Any?) -> Unit
) {
    obj.javaClass.allDeclaredFieldWithSuperclasses.forEach { field ->
        if (!traverseStaticFields && Modifier.isStatic(field.modifiers)) return@forEach
        val result = readFieldSafely(obj, field)
        // do not pass non-readable fields
        if (result.isSuccess) {
            val fieldValue = result.getOrNull()
            onField(obj, field, fieldValue)
        }
    }
}

/**
 * Extension property to determine if an object is of an immutable type.
 */
internal val Any?.isImmutable get() = when {
    this.isPrimitive        -> true
    this is Unit            -> true
    this is String          -> true
    this is BigInteger      -> true
    this is BigDecimal      -> true
    this.isCoroutinesSymbol -> true
    else                    -> false
}

/**
 * Extension property to determine if an object is of a primitive type.
 */
internal val Any?.isPrimitive get() = when (this) {
    is Boolean, is Int, is Short, is Long, is Double, is Float, is Char, is Byte -> true
    else -> false
}

/**
 * Extension property to determine if the given object is a [kotlinx.coroutines] symbol.
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private val Any?.isCoroutinesSymbol get() =
    this is kotlinx.coroutines.internal.Symbol

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