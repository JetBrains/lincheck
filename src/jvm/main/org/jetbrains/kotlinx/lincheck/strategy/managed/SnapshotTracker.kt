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
import org.jetbrains.kotlinx.lincheck.strategy.managed.SnapshotTracker.MemoryNode.*
import org.jetbrains.lincheck.util.*
import java.lang.Class
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.Stack
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray


/**
 * Manages a snapshot of the global static state or manually added *root*-objects.
 *
 * This class tracks static memory and memory reachable from it,
 * referenced from the test code. So the whole static state is not recorded, but only a subset of that,
 * which is named *snapshot*.
 *
 * Also, manual addition of required to restore objects is possible.
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

    fun trackField(obj: Any?, accessClass: Class<*>, fieldName: String) {
        if (obj != null && !isTracked(obj)) return
        trackFieldImpl(
            obj = obj,
            clazz = getDeclaringClass(obj, accessClass, fieldName),
            fieldName = fieldName
        )
    }

    fun trackField(obj: Any?, accessClassName: String, fieldName: String) {
        if (obj != null && !isTracked(obj)) return
        trackFieldImpl(
            obj = obj,
            clazz = getDeclaringClass(obj, accessClassName, fieldName),
            fieldName = fieldName
        )
    }

    private fun trackFieldImpl(obj: Any?, clazz: Class<*>, fieldName: String) {
        val field = clazz.findField(fieldName)
        val readResult = readFieldSafely(obj, field)

        if (readResult.isSuccess) {
            val fieldValue = readResult.getOrNull()
            if (
                trackSingleField(obj, clazz, field, fieldValue) &&
                shouldTrackEagerly(fieldValue)
            ) {
                trackReachableObjectSubgraph(fieldValue!!)
            }
        }
    }

    fun trackArrayCell(array: Any, index: Int) {
        if (!isTracked(array)) return

        val readResult = runCatching { readArrayElementViaUnsafe(array, index) }

        if (readResult.isSuccess) {
            val elementValue = readResult.getOrNull()
            if (
                trackSingleArrayCell(array, index, elementValue) &&
                shouldTrackEagerly(elementValue)
            ) {
                trackReachableObjectSubgraph(elementValue!!)
            }
        }
    }

    /**
     * Starts tracking provided [obj], fields of which later will be restored.
     */
    fun trackObjectAsRoot(obj: Any) {
        trackedObjects.putIfAbsent(obj, mutableListOf())
    }

    /**
     * Tracks all objects reachable from [objs], but only for those which are already tracked.
     */
    fun trackObjects(objs: Array<Any?>) {
        // in case this works too slowly, an optimization could be used
        // see https://github.com/JetBrains/lincheck/pull/418/commits/eb9a9a25f0c57e5b5bdf55dac8f38273ffc7dd8a#diff-a684b1d7deeda94bbf907418b743ae2c0ec0a129760d3b87d00cdf5adfab56c4R146-R199
        objs
            .filter { it != null && isTracked(it) }
            .forEach { trackReachableObjectSubgraph(it!!) }
    }

    fun restoreValues() {
        val visitedObjects = identityHashSetOf<Any>()
        trackedObjects.keys.forEach { restoreValues(it, visitedObjects) }
    }

    private fun isTracked(obj: Any): Boolean = obj in trackedObjects

    /**
     * @return `true` if the [fieldValue] is a trackable object, and it is added
     * as a parent object for its own fields for further lazy tracking.
     */
    private fun trackSingleField(
        obj: Any?,
        clazz: Class<*>,
        field: Field,
        fieldValue: Any?
    ): Boolean {
        val nodesList =
            if (obj != null) trackedObjects[obj]
            else trackedObjects.getOrPut(clazz) { mutableListOf<MemoryNode>() }

        if (
            nodesList == null || // parent object is not tracked
            nodesList
                .filterIsInstance<FieldNode>()
                .any { it.field.name == field.name } // field is already tracked
        ) return false

        val childNode = createFieldNode(obj, field, fieldValue)

        nodesList.add(childNode)
        val isFieldValueTrackable = isTrackableObject(childNode.initialValue)

        if (isFieldValueTrackable) {
            trackedObjects.putIfAbsent(childNode.initialValue, mutableListOf<MemoryNode>())
        }

        return isFieldValueTrackable
    }

    /**
     * @return `true` if the [elementValue] is a trackable object, and it is added
     * as a parent object for its own fields for further lazy tracking.
     */
    private fun trackSingleArrayCell(
        array: Any,
        index: Int,
        elementValue: Any?
    ): Boolean {
        val nodesList = trackedObjects[array]

        if (
            nodesList == null || // array is not tracked
            nodesList.any { it is ArrayCellNode && it.index == index } // this array cell is already tracked
        ) return false

        val childNode = createArrayCellNode(index, elementValue)

        nodesList.add(childNode)
        val isElementValueTrackable = isTrackableObject(childNode.initialValue)

        if (isElementValueTrackable) {
            trackedObjects.putIfAbsent(childNode.initialValue, mutableListOf<MemoryNode>())
        }

        return isElementValueTrackable
    }

    private fun trackReachableObjectSubgraph(obj: Any) {
        traverseObjectGraph(
            obj,
            onArrayElement = { array, index, elementValue ->
                trackSingleArrayCell(array, index, elementValue)
                elementValue
            },
            onField = { owner, field, fieldValue ->
                // optimization to track only `value` field of java atomic classes
                if (isJavaAtomic(owner) && field.name != "value") null
                // do not traverse fields of var-handles
                else if (owner.javaClass.typeName == "java.lang.invoke.VarHandle") null
                else {
                    trackSingleField(owner, owner.javaClass, field, fieldValue)
                    fieldValue
                }
            }
        )
    }

    private fun shouldTrackEagerly(obj: Any?): Boolean {
        if (obj == null) return false
        return (
            // TODO: after support for System.arraycopy,
            //  rewrite to `obj.javaClass.name.startsWith("java.util.concurrent.") && obj.javaClass.name.contains("Atomic")`
            obj.javaClass.name.startsWith("java.util.")
        )
    }

    private fun restoreValues(root: Any, visitedObjects: MutableSet<Any>) {
        val stackOfObjects: Stack<Any> = Stack()
        stackOfObjects.push(root)

        while (stackOfObjects.isNotEmpty()) {
            val obj = stackOfObjects.pop()
            if (obj in visitedObjects) continue
            visitedObjects.add(obj)

            trackedObjects[obj]!!
                .forEach { node ->
                    if (node is ArrayCellNode) {
                        val index = node.index
                        val initialValue = node.initialValue

                        when (obj) {
                            // No need to add support for writing to atomicfu array elements,
                            // because atomicfu arrays are compiled to atomic java arrays
                            is AtomicReferenceArray<*> -> @Suppress("UNCHECKED_CAST") (obj as AtomicReferenceArray<Any?>).set(
                                index,
                                initialValue
                            )

                            is AtomicIntegerArray -> obj.set(index, initialValue as Int)
                            is AtomicLongArray -> obj.set(index, initialValue as Long)
                            else -> writeArrayElementViaUnsafe(obj, index, initialValue)
                        }
                    } else if (!Modifier.isFinal((node as FieldNode).field.modifiers)) {
                        writeFieldViaUnsafe(
                            if (node is StaticFieldNode) null else obj,
                            node.field,
                            node.initialValue
                        )
                    }

                    if (isTrackableObject(node.initialValue)) {
                        stackOfObjects.push(node.initialValue!!)
                    }
                }
        }
    }

    private fun isTrackableObject(value: Any?): Boolean {
        return (
             value != null &&
            !value.javaClass.isPrimitive &&
            !value.javaClass.isEnum &&
            !value.isPrimitive
        )
    }

    private fun getDeclaringClass(obj: Any?, className: String, fieldName: String): Class<*> {
        val clazz = ClassCache.forName(className)
        return getDeclaringClass(obj, clazz, fieldName)
    }

    private fun getDeclaringClass(obj: Any?, clazz: Class<*>, fieldName: String): Class<*> =
        if (obj != null) {
            clazz
        } else {
            clazz.findField(fieldName).declaringClass
        }

    private fun createFieldNode(obj: Any?, field: Field, value: Any?): MemoryNode {
        return if (obj == null) {
            StaticFieldNode(field, value)
        }
        else {
            RegularFieldNode(field, value)
        }
    }

    private fun createArrayCellNode(index: Int, value: Any?): MemoryNode {
        return ArrayCellNode(index, value)
    }
}