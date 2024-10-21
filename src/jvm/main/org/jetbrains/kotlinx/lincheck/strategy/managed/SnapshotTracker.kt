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

import org.jetbrains.kotlinx.lincheck.canonicalClassName
import org.jetbrains.kotlinx.lincheck.findField
import org.jetbrains.kotlinx.lincheck.getArrayLength
import org.jetbrains.kotlinx.lincheck.getFieldOffset
import org.jetbrains.kotlinx.lincheck.strategy.managed.SnapshotTracker.ArrayCellNode
import org.jetbrains.kotlinx.lincheck.util.allDeclaredFieldWithSuperclasses
import org.jetbrains.kotlinx.lincheck.util.readArrayElementViaUnsafe
import org.jetbrains.kotlinx.lincheck.util.readFieldViaUnsafe
import org.jetbrains.kotlinx.lincheck.util.writeArrayElementViaUnsafe
import org.jetbrains.kotlinx.lincheck.util.writeFieldViaUnsafe
import java.lang.reflect.Field
import java.lang.reflect.Modifier


/**
 * Manages a snapshot of the global static state.
 *
 * This class only tracks static memory and memory reachable from it,
 * referenced from the test code. So the whole static state is not recorded, but only a subset of that,
 * which is named *snapshot*.
 */
class SnapshotTracker {
    private val snapshotRoots = mutableListOf<StaticFieldNode>()
    private val trackedStaticFields = mutableMapOf<String, MutableSet<Long>>() // CLASS_NAME -> { OFFSETS_OF_STATIC_FIELDS }

    // TODO: is `className` always required to be declaringClassName (both for static and non-static fields)?
    //  right now `declaringClassName` is not calculated, which is incorrect, but I will fix it later
    private data class NodeDescriptor(
        val className: String, // name of the class which contains the field
        val field: Field,
        val offset: Long // for arrays, offset will be element index
    ) {
        override fun toString(): String {
            return "NodeDescriptor(className=$className, field=${field}, offset=$offset)"
        }
    }

    private abstract class MemoryNode(
        val descriptor: NodeDescriptor,
        val initialValue: Any?,
        val fields: MutableList<MemoryNode> = mutableListOf<MemoryNode>() /* list of fields/array cells that are inside this object */
    )
    private class StaticFieldNode(descriptor: NodeDescriptor, initialValue: Any?) : MemoryNode(descriptor, initialValue)
    private class RegularFieldNode(descriptor: NodeDescriptor, initialValue: Any?) : MemoryNode(descriptor, initialValue)
    private class ArrayCellNode(descriptor: NodeDescriptor, initialValue: Any?) : MemoryNode(descriptor, initialValue)

    /**
     * Remembers the current value of the static variable passed to it and the values of all its fields.
     * The values that will be observed from the provided static variable will be restored on
     * subsequent call to [restoreValues].
     *
     * If the provided static variable is already present in the graph, then nothing will happen.
     */
    fun addHierarchy(className: String, fieldName: String) {
        check(className == className.canonicalClassName) { "Class name must be canonical" }

        val clazz = Class.forName(className)
        val field = clazz.findField(fieldName)
        val offset = getFieldOffset(field)
        val descriptor = NodeDescriptor(className, field, offset)
        val initialValue = readFieldViaUnsafe(null, descriptor.field)
        check(Modifier.isStatic(field.modifiers)) { "Root field in the snapshot hierarchy must be static" }

        if (!trackStaticField(className, offset)) return

        val root = StaticFieldNode(descriptor, initialValue)
        snapshotRoots.add(root)

        addToGraph(root)
    }

    /**
     * Traverses all top-level static variables of the snapshot and restores all
     * reachable fields to initial values recorded by previous calls to [addHierarchy].
     */
    fun restoreValues() {
        val visitedNodes = mutableSetOf<MemoryNode>()
        snapshotRoots.forEach { rootNode ->
            restoreValues(rootNode, null, visitedNodes)
        }
    }


    @OptIn(ExperimentalStdlibApi::class)
    private fun addToGraph(node: MemoryNode, visitedObjects: MutableSet<Any> = mutableSetOf()) {
        if (node.initialValue in visitedObjects) return

        val nodeClass: Class<*> = node.descriptor.field.let {
            if (node is ArrayCellNode) it.type.componentType
            else it.type
        }

        if (nodeClass.isPrimitive || nodeClass.isEnum || node.initialValue == null) return

        visitedObjects.add(node.initialValue)

        if (nodeClass.isArray && node !is ArrayCellNode) {
            val array = node.initialValue

            for (index in 0..getArrayLength(array) - 1) {
                val childDescriptor = NodeDescriptor(
                    nodeClass.name,
                    node.descriptor.field,
                    index.toLong()
                )
                val childNode = ArrayCellNode(childDescriptor, readArrayElementViaUnsafe(array, index))

                processChildNode(node, childNode, visitedObjects)
            }
        }
        else {
            nodeClass.allDeclaredFieldWithSuperclasses.forEach { field ->
                val childDescriptor = NodeDescriptor(nodeClass.name, field, getFieldOffset(field))
                val childNode = if (Modifier.isStatic(field.modifiers)) {
                    StaticFieldNode(childDescriptor, readFieldViaUnsafe(null, childDescriptor.field))
                } else {
                    RegularFieldNode(childDescriptor, readFieldViaUnsafe(node.initialValue, childDescriptor.field))
                }

                processChildNode(node, childNode, visitedObjects)
            }
        }
    }

    private fun processChildNode(parentNode: MemoryNode, childNode: MemoryNode, visitedObjects: MutableSet<Any>) {
        if (
            childNode is StaticFieldNode &&
            !trackStaticField(childNode.descriptor.className, childNode.descriptor.offset)
        ) return

        parentNode.fields.add(childNode)
        addToGraph(childNode, visitedObjects)
    }

    /**
     * @return `true` if static field located in class [className] by [offset] was not tracked before, `false` otherwise.
     */
    private fun trackStaticField(className: String, offset: Long): Boolean {
        trackedStaticFields.putIfAbsent(className, HashSet())
        return trackedStaticFields[className]!!.add(offset)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun restoreValues(node: MemoryNode, parent: MemoryNode? = null, visitedNodes: MutableSet<MemoryNode>) {
        if (node in visitedNodes) return
        visitedNodes.add(node)

        if (!Modifier.isFinal(node.descriptor.field.modifiers)) {
            val obj: Any? =
                if (node is StaticFieldNode) null
                else {
                    check(parent != null) { "Regular field in snapshot hierarchy must have a parent node" }
                    parent.initialValue
                }

            if (node.descriptor.field.type.isArray && node is ArrayCellNode) {
                val array = obj!!
                val index = node.descriptor.offset.toInt()
                writeArrayElementViaUnsafe(array, index, node.initialValue)
            }
            else {
                writeFieldViaUnsafe(obj, node.descriptor.field, node.initialValue)
            }
        }

        node.fields.forEach{ childNode -> restoreValues(childNode, node, visitedNodes) }
    }
}