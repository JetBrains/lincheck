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
import org.jetbrains.kotlinx.lincheck.getFieldOffset
import org.jetbrains.kotlinx.lincheck.isPrimitiveWrapper
import org.jetbrains.kotlinx.lincheck.strategy.managed.SnapshotTracker.Descriptor.*
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

    private sealed class Descriptor {
        class FieldDescriptor(val field: Field, val offset: Long) : Descriptor()
        class ArrayCellDescriptor(val index: Int) : Descriptor()
    }

    private sealed class MemoryNode(
        val descriptor: Descriptor,
        val initialValue: Any?
    ) {
        class RegularFieldNode(descriptor: FieldDescriptor, initialValue: Any?) : MemoryNode(descriptor, initialValue)
        class StaticFieldNode(descriptor: FieldDescriptor, initialValue: Any?) : MemoryNode(descriptor, initialValue)
        class ArrayCellNode(descriptor: ArrayCellDescriptor, initialValue: Any?) : MemoryNode(descriptor, initialValue)
    }

    @OptIn(ExperimentalStdlibApi::class)
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

    @OptIn(ExperimentalStdlibApi::class)
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
        val processField = { owner: Any, field: Field, fieldValue: Any? ->
            trackSingleField(owner, owner.javaClass, field, fieldValue) {
                if (shouldTrackEnergetically(fieldValue) /* also checks for `fieldValue != null` */) {
                    trackHierarchy(fieldValue!!)
                }
            }
        }

        objs
            // leave only those objects from constructor arguments that were tracked before the call to constructors itself
            .filter { it != null && it in trackedObjects }
            .forEach { obj ->
            // We want to track the following values:
            // 1. objects themselves (already tracked because of the filtering)
            // 2. 1st layer of fields of these objects (tracking the whole hierarchy is too expensive, and full laziness does not work,
            //    because of the JVM class verificator limitations, see https://github.com/JetBrains/lincheck/issues/424, thus, we collect
            //    fields which afterward can be used for further lazy tracking)
            // 3. values that are subclasses of the objects' class and their 1st layer of fields
            //    (we are not sure if they are going to require restoring, but we still add them preventively,
            //    again, because there is verificator limitation on tracking such values lazily)
            traverseObjectGraph(
                obj!!,
                // `obj` cannot be an array because it is the same type as some class, which constructor was called, and arrays have not constructors
                onArrayElement = null,
                onField = { owner, field, fieldValue ->
                    when {
                        // add 1st layer of fields of `obj` (2)
                        obj == owner -> {
                            processField(owner, field, fieldValue)
                            // track subclasses of `obj` and their 1st layer of fields (bullet-point 3) recursively
                            if (fieldValue?.javaClass?.isInstance(obj) == true) {
                                // allow traversing further, because `obj`'s 1st layer contains an object which is a subclass of `obj`
                                // the `owner` of `fieldValue` is already added to `trackedObjects` (because `owner == obj`)
                                fieldValue
                            }
                            else {
                                null
                            }
                        }

                        // track subclasses of `obj` and their 1st layer of fields (3)
                        fieldValue != null && fieldValue.javaClass.isInstance(obj) -> {
                            // track the `owner` of `fieldValue`, because otherwise we will not be able to
                            // restore the initial value of `fieldValue` object
                            trackedObjects.putIfAbsent(owner, mutableListOf<MemoryNode>())
                            processField(owner, field, fieldValue)
                            fieldValue
                        }

                        else -> null
                    }
                }
            )
        }
    }

    fun restoreValues() {
        val visitedObjects = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        trackedObjects.keys
            .filterIsInstance<Class<*>>()
            .forEach { restoreValues(it, visitedObjects) }
    }

    @OptIn(ExperimentalStdlibApi::class)
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
                .map { it.descriptor }
                .filterIsInstance<FieldDescriptor>()
                .any { it.field.name == field.name } // field is already tracked
        ) return

        val childNode = createMemoryNode(
            obj,
            FieldDescriptor(field, getFieldOffset(field)),
            fieldValue
        )

        nodesList.add(childNode)
        if (isTrackableObject(childNode.initialValue)) {
            trackedObjects.putIfAbsent(childNode.initialValue, mutableListOf<MemoryNode>())
            callback?.invoke()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun trackSingleArrayCell(array: Any, index: Int, elementValue: Any?, callback: (() -> Unit)? = null) {
        val nodesList = trackedObjects[array]

        if (
            nodesList == null || // array is not tracked
            nodesList.any { it is ArrayCellNode && (it.descriptor as ArrayCellDescriptor).index == index } // this array cell is already tracked
        ) return

        val childNode = createMemoryNode(array, ArrayCellDescriptor(index), elementValue)

        nodesList.add(childNode)
        if (isTrackableObject(childNode.initialValue)) {
            trackedObjects.putIfAbsent(childNode.initialValue, mutableListOf<MemoryNode>())
            callback?.invoke()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
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
            //  (and only check for java atomic classes inserted), see https://github.com/JetBrains/lincheck/pull/418#issue-2595977113
            //  right it is need for collections to be restored properly (because of missing support for `System.arrayCopy()` and other similar methods)
            obj.javaClass.name.startsWith("java.util.")
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun restoreValues(obj: Any, visitedObjects: MutableSet<Any>) {
        if (obj in visitedObjects) return
        visitedObjects.add(obj)

        trackedObjects[obj]!!
            .forEach { node ->
                if (node is ArrayCellNode) {
                    val index = (node.descriptor as ArrayCellDescriptor).index

                    when (obj) {
                        // No need to add support for writing to atomicfu array elements,
                        // because atomicfu arrays are compiled to atomic java arrays
                        is AtomicReferenceArray<*> -> @Suppress("UNCHECKED_CAST") (obj as AtomicReferenceArray<Any?>).set(index, node.initialValue)
                        is AtomicIntegerArray -> obj.set(index, node.initialValue as Int)
                        is AtomicLongArray -> obj.set(index, node.initialValue as Long)
                        else -> writeArrayElementViaUnsafe(obj, index, node.initialValue)
                    }
                }
                else if (!Modifier.isFinal((node.descriptor as FieldDescriptor).field.modifiers)) {
                    writeFieldViaUnsafe(
                        if (node is StaticFieldNode) null else obj,
                        node.descriptor.field,
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

    private fun createMemoryNode(obj: Any?, descriptor: Descriptor, value: Any?): MemoryNode {
        return when (descriptor) {
            is FieldDescriptor -> {
                if (obj == null) StaticFieldNode(descriptor, value)
                else RegularFieldNode(descriptor, value)
            }
            is ArrayCellDescriptor -> ArrayCellNode(descriptor, value)
        }
    }
}