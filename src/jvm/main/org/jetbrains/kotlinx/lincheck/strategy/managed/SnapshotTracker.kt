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

import org.jetbrains.kotlinx.lincheck.findField
import org.jetbrains.kotlinx.lincheck.isPrimitiveWrapper
import org.jetbrains.kotlinx.lincheck.strategy.managed.SnapshotTracker.MemoryNode.*
import org.jetbrains.kotlinx.lincheck.util.*
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray


/**
 * Manages a snapshot of the global static state.
 *
 * This class only tracks static memory and memory reachable from it,
 * referenced from the test code. So the whole static state is not recorded, but only a subset of that,
 * which is named *snapshot*.
 */
class SnapshotTracker {
    private val trackedObjects = IdentityHashMap<Any, MutableList<MemoryNode>>()

    private sealed class MemoryNode(
        val initialValue: Any?
    ) {
        abstract class FieldNode(val field: Field, initialValue: Any?) : MemoryNode(initialValue)

        class RegularFieldNode(field: Field, initialValue: Any?) : FieldNode(field, initialValue)
        class StaticFieldNode(field: Field, initialValue: Any?) : FieldNode(field, initialValue)
        class ArrayCellNode(val index: Int, initialValue: Any?) : MemoryNode(initialValue)
    }

    fun trackField(obj: Any?, className: String, fieldName: String) {
        if (obj != null && obj !in trackedObjects) return

        val clazz: Class<*> = Class.forName(className).let {
            if (obj != null) it
            else it.findField(fieldName).declaringClass
        }
        val field = clazz.findField(fieldName)
        val readResult = runCatching { readFieldViaUnsafe(obj, field) }

        if (readResult.isSuccess) {
            val fieldValue = readResult.getOrNull()
            trackSingleField(obj, clazz, field, fieldValue) {
                if (shouldTrackEnergetically(fieldValue)) {
                    trackHierarchy(fieldValue!!)
                }
            }
        }
    }

    fun trackArrayCell(array: Any, index: Int) {
        if (array !in trackedObjects) return

        val readResult = runCatching { readArrayElementViaUnsafe(array, index) }

        if (readResult.isSuccess) {
            val elementValue = readResult.getOrNull()
            trackSingleArrayCell(array, index, elementValue) {
                if (shouldTrackEnergetically(elementValue)) {
                    trackHierarchy(elementValue!!)
                }
            }
        }
    }

    fun trackObjects(objs: Array<Any?>) {
        // in case this works too slowly, an optimization could be used
        // see https://github.com/JetBrains/lincheck/pull/418/commits/0d708b84dd2bfd5dbfa961549dda128d91dc3a5b#diff-a684b1d7deeda94bbf907418b743ae2c0ec0a129760d3b87d00cdf5adfab56c4R146-R199
        objs
            .filter { it != null && it in trackedObjects }
            .forEach { trackHierarchy(it!!) }
    }

    fun restoreValues() {
        val visitedObjects = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        trackedObjects.keys
            .filterIsInstance<Class<*>>()
            .forEach { restoreValues(it, visitedObjects) }
    }

    private fun trackSingleField(
        obj: Any?,
        clazz: Class<*>,
        field: Field,
        fieldValue: Any?,
        callback: (() -> Unit)? = null
    ) {
        val nodesList =
            if (obj != null) trackedObjects[obj]
            else trackedObjects.getOrPut(clazz) { mutableListOf<MemoryNode>() }

        if (
            nodesList == null || // parent object is not tracked
            nodesList
                .filterIsInstance<FieldNode>()
                .any { it.field.name == field.name } // field is already tracked
        ) return

        val childNode = createFieldNode(obj, field, fieldValue)

        nodesList.add(childNode)
        if (isTrackableObject(childNode.initialValue)) {
            trackedObjects.putIfAbsent(childNode.initialValue, mutableListOf<MemoryNode>())
            callback?.invoke()
        }
    }

    private fun trackSingleArrayCell(array: Any, index: Int, elementValue: Any?, callback: (() -> Unit)? = null) {
        val nodesList = trackedObjects[array]

        if (
            nodesList == null || // array is not tracked
            nodesList.any { it is ArrayCellNode && it.index == index } // this array cell is already tracked
        ) return

        val childNode = createArrayCellNode(index, elementValue)

        nodesList.add(childNode)
        if (isTrackableObject(childNode.initialValue)) {
            trackedObjects.putIfAbsent(childNode.initialValue, mutableListOf<MemoryNode>())
            callback?.invoke()
        }
    }

    private fun trackHierarchy(obj: Any) {
        traverseObjectGraph(
            obj,
            onArrayElement = { array, index, elementValue ->
                trackSingleArrayCell(array, index, elementValue)
                elementValue
            },
            onField = { owner, field, fieldValue ->
                // optimization to track only `value` field of java atomic classes
                if (isAtomicJava(owner) && field.name != "value") null
                // do not traverse fields of var-handles
                else if (owner.javaClass.typeName == "java.lang.invoke.VarHandle") null
                else {
                    trackSingleField(owner, owner.javaClass, field, fieldValue)
                    fieldValue
                }
            }
        )
    }

    private fun shouldTrackEnergetically(obj: Any?): Boolean {
        if (obj == null) return false
        return (
            // TODO: in further development of snapshot restoring feature this check should be removed
            //  (and only check for java atomic classes should be inserted), see https://github.com/JetBrains/lincheck/pull/418#issue-2595977113
            //  right now it is needed for collections to be restored properly (because of missing support for `System.arrayCopy()` and other similar methods)
            obj.javaClass.name.startsWith("java.util.")
        )
    }

    private fun restoreValues(obj: Any, visitedObjects: MutableSet<Any>) {
        if (obj in visitedObjects) return
        visitedObjects.add(obj)

        trackedObjects[obj]!!
            .forEach { node ->
                if (node is ArrayCellNode) {
                    val index = node.index
                    val initialValue = node.initialValue

                    when (obj) {
                        // No need to add support for writing to atomicfu array elements,
                        // because atomicfu arrays are compiled to atomic java arrays
                        is AtomicReferenceArray<*> -> @Suppress("UNCHECKED_CAST") (obj as AtomicReferenceArray<Any?>).set(index, initialValue)
                        is AtomicIntegerArray -> obj.set(index, initialValue as Int)
                        is AtomicLongArray -> obj.set(index, initialValue as Long)
                        else -> writeArrayElementViaUnsafe(obj, index, initialValue)
                    }
                }
                else if (!Modifier.isFinal((node as FieldNode).field.modifiers)) {
                    writeFieldViaUnsafe(
                        if (node is StaticFieldNode) null else obj,
                        node.field,
                        node.initialValue
                    )
                }

                if (isTrackableObject(node.initialValue)) {
                    restoreValues(node.initialValue!!, visitedObjects)
                }
            }
    }

    private fun isTrackableObject(value: Any?): Boolean {
        return (
             value != null &&
            !value.javaClass.isPrimitive &&
            !value.javaClass.isEnum &&
            !value.isPrimitiveWrapper
        )
    }

    private fun createFieldNode(obj: Any?, field: Field, value: Any?): MemoryNode {
        return if (obj == null) StaticFieldNode(field, value)
               else RegularFieldNode(field, value)
    }

    private fun createArrayCellNode(index: Int, value: Any?): MemoryNode {
        return ArrayCellNode(index, value)
    }
}