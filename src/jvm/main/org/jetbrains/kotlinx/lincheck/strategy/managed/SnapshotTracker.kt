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
import org.jetbrains.kotlinx.lincheck.strategy.managed.SnapshotTracker.Descriptor.ArrayCellDescriptor
import org.jetbrains.kotlinx.lincheck.strategy.managed.SnapshotTracker.Descriptor.FieldDescriptor
import org.jetbrains.kotlinx.lincheck.strategy.managed.SnapshotTracker.MemoryNode.ArrayCellNode
import org.jetbrains.kotlinx.lincheck.strategy.managed.SnapshotTracker.MemoryNode.RegularFieldNode
import org.jetbrains.kotlinx.lincheck.strategy.managed.SnapshotTracker.MemoryNode.StaticFieldNode
import org.jetbrains.kotlinx.lincheck.util.readArrayElementViaUnsafe
import org.jetbrains.kotlinx.lincheck.util.readFieldViaUnsafe
import org.jetbrains.kotlinx.lincheck.util.writeArrayElementViaUnsafe
import org.jetbrains.kotlinx.lincheck.util.writeFieldViaUnsafe
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap


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

    fun trackField(obj: Any?, className: String, fieldName: String) {
        val clazz: Class<*> = Class.forName(className).let {
            if (obj != null) it
            else it.findField(fieldName).declaringClass
        }

        val nodesList =
            if (obj != null) trackedObjects[obj]
            else trackedObjects.getOrPut(clazz) { mutableListOf<MemoryNode>() }

        if (
            nodesList == null || // parent object is not tracked
            nodesList
                .map { it.descriptor }
                .filterIsInstance<FieldDescriptor>()
                .any { it.field.name == fieldName } // field is already tracked
        ) return

        println("SNAPSHOT: obj=$obj, className=$className, fieldName=$fieldName")
        val field = clazz.findField(fieldName)
        val childNode = createMemoryNode(
            obj,
            FieldDescriptor(field, getFieldOffset(field))
        )

        nodesList.add(childNode)
        if (isTrackableObject(childNode.initialValue)) {
            trackedObjects.putIfAbsent(childNode.initialValue, mutableListOf<MemoryNode>())
        }
    }

    fun trackArrayCell(array: Any, index: Int) {
        val nodesList = trackedObjects[array]

        if (
            nodesList == null || // array is not tracked
            nodesList.any { it is ArrayCellNode && (it.descriptor as ArrayCellDescriptor).index == index } // this array cell is already tracked
        ) return

        println("SNAPSHOT: array=$array, index=$index")
        val childNode = createMemoryNode(array, ArrayCellDescriptor(index))

        nodesList.add(childNode)
        if (isTrackableObject(childNode.initialValue)) {
            trackedObjects.putIfAbsent(childNode.initialValue, mutableListOf<MemoryNode>())
        }
    }

    fun restoreValues() {
        val visitedObjects = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        trackedObjects.keys
            .filterIsInstance<Class<*>>()
            .forEach { restoreValues(it, visitedObjects) }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun restoreValues(obj: Any, visitedObjects: MutableSet<Any>) {
        if (obj in visitedObjects) return
        visitedObjects.add(obj)

        trackedObjects[obj]!!
            .forEach { node ->
                if (node is ArrayCellNode) {
                    val index = (node.descriptor as ArrayCellDescriptor).index
                    writeArrayElementViaUnsafe(obj, index, node.initialValue)
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
            !value.javaClass.isEnum
        )
    }

    private fun createMemoryNode(obj: Any?, descriptor: Descriptor): MemoryNode {
        return when (descriptor) {
            is FieldDescriptor -> {
                if (obj is Class<*>) StaticFieldNode(descriptor, readFieldViaUnsafe(null, descriptor.field))
                else RegularFieldNode(descriptor, readFieldViaUnsafe(obj, descriptor.field))
            }
            is ArrayCellDescriptor -> ArrayCellNode(descriptor, readArrayElementViaUnsafe(obj!!, descriptor.index))
        }
    }
}