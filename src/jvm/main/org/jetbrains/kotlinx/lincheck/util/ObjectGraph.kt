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
 * Traverses all fields of a given object in bfs order.
 *
 * In case if a reached object is java array, atomic array, or atomicfu array,
 * the elements of such arrays will be traversed and [onArrayElement] will be called on them.
 *
 * Otherwise, fields are traversed and [onField] is called.
 *
 * @param root object from which the traverse starts.
 * @param onArrayElement callback for array elements, accepts `(array, index, elementValue)`. Returns what object should be traversed next.
 * @param onField callback for fields, accepts `(fieldOwner, field, fieldValue)`. Returns what object should be traversed next.
 */
internal fun traverseObjectGraph(
    root: Any,
    onArrayElement: (Any, Int, Any?) -> Any?,
    onField: (Any, Field, Any?) -> Any?
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

    val process: (
        onRead: () -> Any?,
        onCallback: (Any?) -> Any?
    ) -> Unit = { onRead, onCallback ->
        // We wrap an unsafe read into `runCatching` to hande `UnsupportedOperationException`,
        // which can be thrown, for instance, when attempting to read a field of
        // a hidden class (starting from Java 15).
        val result = runCatching { onRead() }

        // do not pass fields to the user, that are non-readable by Unsafe
        if (result.isSuccess) {
            val fieldValue = result.getOrNull()
            val nextObject = onCallback(fieldValue)

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
    }

    while (queue.isNotEmpty()) {
        val currentObj = queue.removeFirst()

        when {
            currentObj is Class<*> -> {}
            currentObj.javaClass.isArray || isAtomicArray(currentObj) -> {
                val length = getArrayLength(currentObj)
                // TODO: casting currentObj to atomicfu class and accessing its field directly causes compilation error,
                //  see https://youtrack.jetbrains.com/issue/KT-49792 and https://youtrack.jetbrains.com/issue/KT-47749
                val cachedAtomicFUGetMethod: Method? = if (isAtomicFUArray(currentObj)) currentObj.javaClass.getMethod("get", Int::class.java) else null

                for (index in 0..length - 1) {
                    process(
                        {
                            if (isAtomicFUArray(currentObj)) {
                                cachedAtomicFUGetMethod!!.invoke(currentObj, index) as Int
                            }
                            else when (currentObj) {
                                is AtomicReferenceArray<*> -> currentObj.get(index)
                                is AtomicIntegerArray -> currentObj.get(index)
                                is AtomicLongArray -> currentObj.get(index)
                                else -> readArrayElementViaUnsafe(currentObj, index)
                            }
                        },
                        { onArrayElement(currentObj, index, it) }
                    )
                }
            }
            else -> {
                currentObj.javaClass.allDeclaredFieldWithSuperclasses.forEach { field ->
                    process(
                        { readFieldViaUnsafe(currentObj, field) },
                        { onField(currentObj, field, it) }
                    )
                }
            }
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
