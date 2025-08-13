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

import java.util.concurrent.atomic.*
import java.lang.reflect.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*


private typealias FieldCallback = (obj: Any, field: Field, value: Any?) -> Any?
private typealias ArrayElementCallback = (array: Any, index: Int, element: Any?) -> Any?
private typealias ObjectCallback = (obj: Any) -> Boolean
private typealias ObjectExpansionCallback = (obj: Any) -> List<Any>

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
 * @param config configuration of the traversal, see [ObjectGraphTraversalConfig].
 * @param processedObjects Optional set to track already processed objects during traversal.
 *   Can be used to maintain object visit state across multiple traversal calls.
 *   If not provided, a new identity-based hash set will be created.
 *   Must use referential equality (identity-based comparison) for correct cycle detection in the traversal algorithm.
 * @param onField callback for fields traversal, accepts `(fieldOwner, field, fieldValue)`.
 *   Returns an object to be traversed next, or null if the field value should not be traversed.
 *   If not passed, by default, all fields of objects are traversed.
 * @param onArrayElement callback for array elements traversal, accepts `(array, index, elementValue)`.
 *   Returns an object to be traversed next, or null if the array element should not be traversed.
 *   If not passed, by default, all array elements are traversed.
 * @param expandObject Optional callback for custom object expansion during traversal,
 *   beyond default fields and array elements traversal.
 *   When provided, should return for an object a list of additional objects to be traversed.
 * @param onObject Optional callback invoked for each distinct object encountered during traversal
 *   before it is traversed recursively.
 *   Should return boolean indicating whether the object should be traversed recursively.
 *   If not provided, defaults to true, indicating that any reached objects should be traversed recursively.
 */
internal fun traverseObjectGraph(
    root: Any,
    processedObjects: MutableSet<Any>? = null,
    config: ObjectGraphTraversalConfig = ObjectGraphTraversalConfig(),
    onField: FieldCallback = { _ /* obj */, _ /* field */, fieldValue -> fieldValue },
    onArrayElement: ArrayElementCallback = { _ /* array */, _ /* index */, elementValue -> elementValue },
    expandObject: ObjectExpansionCallback? = null,
    onObject: ObjectCallback = { _ /* obj */ -> true },
) {
    if (!shouldTraverseObject(root, config)) return
    if (processedObjects != null && root in processedObjects) return
    if (!onObject(root)) return

    val queue = ArrayDeque<Any>()
    val visitedObjects = processedObjects ?: identityHashSetOf()

    queue.add(root)
    visitedObjects.add(root)

    val processNextObject: (nextObject: Any?) -> Unit = { nextObject ->
        val nextObject = promoteObject(nextObject, config)
        if (shouldTraverseObject(nextObject, config) && nextObject !in visitedObjects) {
            if (onObject(nextObject!!)) {
                queue.add(nextObject)
                visitedObjects.add(nextObject)
            }
        }
    }

    while (queue.isNotEmpty()) {
        val currentObj = queue.removeFirst()
        when {
            (currentObj.javaClass.isArray || isAtomicArray(currentObj)) -> {
                traverseArrayElements(currentObj) { _ /* array */, index, elementValue ->
                    processNextObject(onArrayElement(currentObj, index, elementValue))
                }
            }
            else -> {
                traverseObjectFields(
                    currentObj,
                    fieldPredicate = { field ->
                        // do not traverse static fields if static fields traversal is disabled
                        (Modifier.isStatic(field.modifiers) implies config.traverseStaticFields) &&
                        // do not traverse fields of primitive types if immutable objects traversal is disabled
                        // (primitive types are a subset of immutable types)
                        (field.type.isPrimitive implies config.traverseImmutableObjects)
                    }
                ) { _ /* obj */, field, fieldValue ->
                    processNextObject(onField(currentObj, field, fieldValue))
                }
            }
        }
        if (expandObject != null) {
            expandObject(currentObj).forEach {
                processNextObject(it)
            }
        }
    }
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
 * @param fieldPredicate predicate to filter which fields should be traversed.
 *   Should return true if the field should be processed.
 * @param onField callback which accepts `(obj, field, fieldValue)`.
 */
internal inline fun traverseObjectFields(
    obj: Any,
    fieldPredicate: (Field) -> Boolean = { true },
    onField: (obj: Any, field: Field, value: Any?) -> Unit
) {
    for (field in obj.javaClass.allDeclaredFieldWithSuperclasses) {
        if (!fieldPredicate(field)) continue
        val result = readFieldSafely(obj, field)
        // do not pass non-readable fields
        if (result.isSuccess) {
            val fieldValue = result.getOrNull()
            onField(obj, field, fieldValue)
        }
    }
}

private fun promoteObject(obj: Any?, config: ObjectGraphTraversalConfig): Any? {
    var promotedObject = obj
    if (config.promoteAtomicObjects) {
        while (true) {
            when {
                // Special treatment for java atomic classes, because they can be extended but user classes,
                // in case if a user extends java atomic class, we do not want to jump through it.
                (isJavaAtomic(promotedObject)) -> {
                    val getMethod = promotedObject!!.javaClass.getMethod("get")
                    promotedObject = getMethod.invoke(promotedObject)
                }
                // atomicfu.AtomicBool is handled separately because its value field is named differently
                isAtomicFUBoolean(promotedObject) -> {
                    val valueField = promotedObject!!.javaClass.getDeclaredField("_value")
                    promotedObject = readFieldViaUnsafe(promotedObject, valueField)
                }
                // other atomicfu types are handled uniformly
                isAtomicFU(promotedObject) -> {
                    val valueField = promotedObject!!.javaClass.getDeclaredField("value")
                    promotedObject = readFieldViaUnsafe(promotedObject, valueField)
                }
                // otherwise, the next object is not an atomic object, so we exit the promotion loop
                else -> break
            }
        }
    }
    return promotedObject
}

/**
 * Determines whether the given object should be traversed during object graph traversal based on
 * the provided traversal configuration.
 * In addition to that, excludes certain types of objects from traversal,
 * including atomic field updaters, unsafe instance, class objects, and class loaders
 *
 * @param obj The object to evaluate for traversal, may be null.
 * @param config The configuration specifying the rules for object graph traversal.
 * @return `true` if the object should be traversed, `false` otherwise.
 */
internal fun shouldTraverseObject(obj: Any?, config: ObjectGraphTraversalConfig): Boolean =
    obj != null &&
    // no immutable objects traversing, unless specified in the config
    (!obj.isImmutable || config.traverseImmutableObjects) &&
    // no enum objects traversing, unless specified in the config
    (obj !is Enum<*> || config.traverseEnumObjects) &&
    // no afu traversing
    !isAtomicFieldUpdater(obj) &&
    // no unsafe traversing
    !isUnsafeClass(obj.javaClass.name) &&
    // no class objects traversing
    obj !is Class<*> &&
    // no class loader traversing
    obj !is ClassLoader

/**
 * Configuration for controlling the behavior of object graph traversal.
 *
 * @property traverseStaticFields Determines whether static fields of classes should be traversed.
 * @property traverseImmutableObjects Determines whether immutable objects should be traversed
 *   (see [isImmutable] for the list of classes considered immutable).
 * @property traverseEnumObjects Determines whether enum objects should be traversed.
 * @property promoteAtomicObjects Determines whether atomic objects should be promoted during traversal.
 *   Promotion of atomic objects means that instead of the atomic reference object itself,
 *   its referent will be traversed.
 *   That is, instead of `AtomicReference<T>` the `T` object will be traversed,
 *   and instead of `AtomicInteger` the `Integer` object will be traversed.
 */
internal data class ObjectGraphTraversalConfig(
    val traverseStaticFields: Boolean = false,
    val traverseImmutableObjects: Boolean = false,
    val traverseEnumObjects: Boolean = true,
    val promoteAtomicObjects: Boolean = false,
)

/**
 * Extension property to determine if an object is of an immutable type.
 */
internal val Any?.isImmutable get() = when {
    this.isPrimitive        -> true
    this is Unit            -> true
    this is String          -> true
    this is BigInteger      -> true
    this is BigDecimal      -> true
    else                    -> false
}

/**
 * Extension property to determine if an object is of a primitive type.
 */
internal val Any?.isPrimitive get() = when (this) {
    is Boolean, is Int, is Short, is Long, is Double, is Float, is Char, is Byte -> true
    else -> false
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