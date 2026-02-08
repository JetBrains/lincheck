/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util.tree

import org.jetbrains.lincheck.util.*
import org.jetbrains.lincheck.util.collections.*

// ========================================================
//   Tree and Node interfaces and basic utils
// ========================================================

interface Tree<T> {
    val root: Node<T>?

    interface Node<T> {
        val data: T
        val parent: Node<T>?
        val children: List<Node<T>>

        fun relink(newParent: MutableNode<T>?)
    }

    interface MutableNode<T> : Node<T> {
        override var data: T
        override val children: MutableList<Node<T>>
    }
}

fun <T> Tree<T>.validate() {
    root?.validate()
}

fun <T> Tree.Node<T>.validate() {
    check(children.all { it.parent == this }) {
        "Parent-child references are not consistent"
    }
    children.forEach { it.validate() }
}

fun <T> Tree(root: Tree.Node<T>?): Tree<T> = object : Tree<T> {
    override val root = root

    init {
        require(root?.parent == null) {
            "Root node must not have a parent"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return (other is Tree<*> && root == other.root)
    }

    override fun hashCode(): Int = root.hashCode()

    override fun toString(): String = root.toString()
}

fun <T> Tree.Node<T>.copy(newData: T = this.data): Tree.Node<T> = object : Tree.Node<T> {
    override val data: T = newData
    override var parent = this@copy.parent
        private set
    override val children: List<Tree.Node<T>> = this@copy.children

    override fun relink(newParent: Tree.MutableNode<T>?) {
        parent = newParent
    }
}

fun <T> Tree.Node<T>.unlink() {
    relink(null)
}

// ========================================================
//   Node implementation
// ========================================================

private class NodeImpl<T>(override var data: T) : Tree.MutableNode<T> {
    override var parent: Tree.Node<T>? = null
        private set

    override val children: MutableList<Tree.Node<T>> = ObservableList(
        onSet = { _, old, new ->
            old.unlink()
            new.relink(this)
        },
        onAdd = { it.relink(this) },
        onRemove = { it.unlink() },
        onClear = { elements -> elements.forEach { it.unlink() } },
    )

    constructor(data: T, parent: Tree.Node<T>?) : this(data) {
        this.parent = parent
    }

    override fun relink(newParent: Tree.MutableNode<T>?) {
        parent = newParent
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeImpl<*>) return false

        // Note: nodes are compared "structurally" and are considered equal
        // if their data and children are equal.
        // The `parent` is intentionally excluded from the equality check,
        // as otherwise it would trigger infinite recursion due to circular parent-child references.
        return (data == other.data && children == other.children)
    }

    override fun hashCode(): Int {
        return 31 * (data?.hashCode() ?: 0) + children.hashCode()
    }

    override fun toString(): String {
        fun helper(padding: String, node: Tree.Node<T>): String {
            if (node.children.isEmpty()) return "${padding}node(data=${node.data})"

            return "${padding}node(data=${node.data}) {\n" +
                    node.children.joinToString(separator = "\n") { helper("$padding  ", it) } +
                    "\n$padding}"
        }
        return helper("", this)
    }
}

// ========================================================
//   Tree and Node building DSL
// ========================================================

@DslMarker
annotation class TreeDslMarker

@TreeDslMarker
class NodeBuilder<T>() {
    private val _children = mutableListOf<Tree.Node<T>>()
    val children: List<Tree.Node<T>> get() = _children

    fun node(node: Tree.Node<T>): Tree.Node<T> {
        _children.add(node)
        return node
    }

    fun node(data: T, block: NodeBuilder<T>.() -> Unit = {}): Tree.Node<T> {
        val builder = NodeBuilder<T>().apply(block)
        return builder.buildImpl(data).also {
            node -> _children.add(node)
        }
    }

    fun build(data: T): Tree.Node<T> = buildImpl(data)

    private fun buildImpl(data: T): NodeImpl<T> =
        NodeImpl(data).also { node ->
            node.children.addAll(_children)
        }
}

fun <T> node(data: T, block: NodeBuilder<T>.() -> Unit): Tree.Node<T> {
    val builder = NodeBuilder<T>().apply(block)
    return builder.build(data)
}

fun <T> tree(block: NodeBuilder<T>.() -> Unit): Tree<T> {
    val builder = NodeBuilder<T>().apply(block)
    require(builder.children.size <= 1) { "Tree must have a single root node" }
    return Tree(root = builder.children.firstOrNull())
}


// ========================================================
//   ForEach
// ========================================================

fun <T> Tree<T>.forEach(action: (T) -> Unit) {
    root?.forEach(action)
}

fun <T> Tree.Node<T>.forEach(action: (T) -> Unit) {
    action(data)
    children.forEach { it.forEach(action) }
}

fun <T> Tree<T>.forEachNode(action: (Tree.Node<T>) -> Unit) {
    root?.forEachNode(action)
}

fun <T> Tree.Node<T>.forEachNode(action: (Tree.Node<T>) -> Unit) {
    action(this)
    children.forEach { it.forEachNode(action) }
}

// ========================================================
//   Map
// ========================================================

fun <T, U> Tree<T>.map(transform: (T) -> U): Tree<U> =
    Tree(root?.map(parent = null, transform))

private fun <T, U> Tree.Node<T>.map(parent: Tree.Node<U>?, transform: (T) -> U): NodeImpl<U> {
    return NodeImpl(transform(data), parent).also { node ->
        node.children.addAll(children.map { it.map(node, transform) })
    }
}

// ========================================================
//   Transform
// ========================================================

fun <T> Tree<T>.transform(transform: (Tree.Node<T>) -> Tree.Node<T>): Tree<T> =
    Tree(root?.transform(parent = null, transform))

private fun <T> Tree.Node<T>.transform(parent: Tree.Node<T>?, transform: (Tree.Node<T>) -> Tree.Node<T>): NodeImpl<T> {
    val transformedNode = transform(this)
    return NodeImpl(transformedNode.data, parent).also { node ->
        node.children.addAll(transformedNode.children.map { it.transform(node, transform) })
    }
}

// ========================================================
//   Find
// ========================================================

fun <T> Tree<T>.find(predicate: (T) -> Boolean): T? {
    return root?.find(predicate)
}

fun <T> Tree.Node<T>.find(predicate: (T) -> Boolean): T? {
    if (predicate(data)) return data
    return children.firstNotNullOfOrNull { it.find(predicate) }
}

fun <T> Tree<T>.findNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? {
    return root?.findNode(predicate)
}

fun <T> Tree.Node<T>.findNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? {
    if (predicate(this)) return this
    return children.firstNotNullOfOrNull { it.findNode(predicate) }
}

fun <T> Tree<T>.findLast(predicate: (T) -> Boolean): T? {
    return root?.findLast(predicate)
}

fun <T> Tree.Node<T>.findLast(predicate: (T) -> Boolean): T? {
    val found = children.lastNotNullOfOrNull { it.findLast(predicate) }
    if (found != null) return found
    if (predicate(data)) return data
    return null
}

fun <T> Tree<T>.findLastNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? {
    return root?.findLastNode(predicate)
}

fun <T> Tree.Node<T>.findLastNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? {
    val found = children.lastNotNullOfOrNull { it.findLastNode(predicate) }
    if (found != null) return found
    if (predicate(this)) return this
    return null
}

// ========================================================
//   Leaf (first/last)
// ========================================================

fun <T> Tree.Node<T>.firstLeaf(): Tree.Node<T>? {
    if (children.isEmpty()) return null
    var currentNode = this
    while (currentNode.children.isNotEmpty()) {
        currentNode = currentNode.children.first()
    }
    return currentNode
}

fun <T> Tree.Node<T>.lastLeaf(): Tree.Node<T>? {
    if (children.isEmpty()) return null
    var currentNode = this
    while (currentNode.children.isNotEmpty()) {
        currentNode = currentNode.children.last()
    }
    return currentNode
}