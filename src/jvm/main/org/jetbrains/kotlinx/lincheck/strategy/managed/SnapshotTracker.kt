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

import org.jetbrains.kotlinx.lincheck.allDeclaredFieldWithSuperclasses
import org.jetbrains.kotlinx.lincheck.canonicalClassName
import org.jetbrains.kotlinx.lincheck.findField
import org.jetbrains.kotlinx.lincheck.getFieldOffset
import org.jetbrains.kotlinx.lincheck.strategy.managed.SnapshotTracker.ArrayCellNode
import org.jetbrains.kotlinx.lincheck.util.readField
import org.jetbrains.kotlinx.lincheck.util.writeField
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
    private val trackedStaticFields = mutableSetOf<NodeDescriptor>()

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
        if (className.startsWith("java.")) return
        check(className == className.canonicalClassName) { "Class name must be canonical" }

        val clazz = Class.forName(className)
        val field = clazz.findField(fieldName)
        val descriptor = NodeDescriptor(className, field, getFieldOffset(field))
        val initialValue = readField(null, descriptor.field)

        check(Modifier.isStatic(field.modifiers)) { "Root field in the snapshot hierarchy must be static" }
        if (descriptor in trackedStaticFields) return

        val root = StaticFieldNode(descriptor, initialValue)
        snapshotRoots.add(root)
        addToGraph(root)
    }

    /**
     * Traverses all top-level static variables of the snapshot and restores all
     * reachable fields to initial values recorded by previous calls to [addHierarchy].
     */
    fun restoreValues() {
//        println("Restoring all memory reachable from static state to snapshot values")

        val visitedNodes = mutableSetOf<MemoryNode>()
        snapshotRoots.forEach { rootNode ->
            restoreValues(rootNode, null, visitedNodes)
        }
    }


    private fun addToGraph(node: MemoryNode, visitedObjects: MutableSet<Any> = mutableSetOf()) {
        if (node.initialValue in visitedObjects) return

        if (node is StaticFieldNode) {
            if (trackedStaticFields.contains(node.descriptor)) return
            trackedStaticFields.add(node.descriptor)
        }

        val nodeClass = node.descriptor.field.let {
            if (node is ArrayCellNode) it.type.componentType
            else it.type
        }

        if (nodeClass.isPrimitive || nodeClass.isEnum || node.initialValue == null) return

//        println(
//            "Adding to hierarchy: ${nodeClass.canonicalName}::${node.descriptor.field.name} =" +
//            node.initialValue + if (nodeClass.isArray && node !is ArrayCellNode) (node.initialValue as Array<*>).contentToString() else ""
//        )

        visitedObjects.add(node.initialValue)

        if (nodeClass.isArray && node !is ArrayCellNode) {
            val array = node.initialValue as Array<*>

            for (index in array.indices) {
                val childDescriptor = NodeDescriptor(
                    nodeClass.canonicalName,
                    node.descriptor.field,
                    index.toLong()
                )

                val childNode = ArrayCellNode(childDescriptor, array[index])

                node.fields.add(childNode)
                addToGraph(childNode, visitedObjects)
            }
        }
        else {
            nodeClass.allDeclaredFieldWithSuperclasses.forEach { field ->
                val childDescriptor = NodeDescriptor(nodeClass.canonicalName, field, getFieldOffset(field))

                val childNode = if (Modifier.isStatic(field.modifiers)) {
                    StaticFieldNode(childDescriptor, readField(null, childDescriptor.field))
                } else {
                    RegularFieldNode(childDescriptor, readField(node.initialValue, childDescriptor.field))
                }

                node.fields.add(childNode)
                addToGraph(childNode, visitedObjects)
            }
        }
    }

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

//            println(
//                "Write to ${node.descriptor.className}::${node.descriptor.field.name} =" +
//                node.initialValue + if (node.descriptor.field.type.isArray && node !is ArrayCellNode) (node.initialValue as Array<*>).contentToString() else ""
//            )

            if (node is ArrayCellNode) {
                @Suppress("UNCHECKED_CAST")
                val array = obj as Array<Any?>
                val index = node.descriptor.offset.toInt()
                array[index] = node.initialValue
            }
            else {
                writeField(obj, node.descriptor.field, node.initialValue)
            }
        }

        node.fields.forEach{ childNode -> restoreValues(childNode, node, visitedNodes) }
    }
}