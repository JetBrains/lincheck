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

import org.jetbrains.lincheck.util.collections.*

// ========================================================
//   Tree and Node interfaces
// ========================================================

/**
 * A tree of values of type [T], possibly empty.
 *
 * Trees are compared by their roots: two trees are equal when their root nodes are equal.
 */
interface Tree<T> {
    /**
     * The root node, or `null` if the tree is empty.
     */
    val root: Node<T>?

    /**
     * A single tree node: a [data] value plus links to the [parent] and the [children].
     *
     * NOTE: nodes are compared "structurally" and are considered equal if their data and children are equal.
     *  The `parent` is intentionally excluded from the equality check,
     *  as otherwise it would trigger infinite recursion due to circular parent-child references.
     */
    interface Node<T> {
        /** The value carried by this node. */
        val data: T

        /** The parent node, or `null` if this node is a root or is detached. */
        val parent: Node<T>?

        /** The child nodes, in order. */
        val children: List<Node<T>>
    }

    /**
     * A [Node] with replaceable [data] and a modifiable children list.
     */
    interface MutableNode<T> : Node<T> {
        override var data: T

        override val parent: MutableNode<T>?

        /**
         * The child nodes; modifications maintain parent links:
         * added nodes are relinked to this node, removed ones are unlinked.
         */
        override val children: MutableList<MutableNode<T>>

        /**
         * Attaches this node to [newParent].
         * The node should be detached from its previous parent beforehand.
         *
         * NOTE: this method does not add the node to its parent's children list;
         *   it is intended to be used only internally from [MutableNode] implementation classes.
         *   In order to add a child node to a parent node,
         *   the clients should use  [MutableList.add] on [MutableNode.children] instead.
         *
         * @throws IllegalStateException if this node is already attached to a different parent.
         */
        fun attach(newParent: MutableNode<T>)

        /**
         * Detaches this node from its parent.
         * Does nothing if this node is already detached.
         *
         * NOTE: this method does not remove the node from its parent's children list;
         *   it is intended to be used only internally from [MutableNode] implementation classes.
         *   In order to remove a child node from a parent node,
         *   the clients should use  [MutableList.remove] on [MutableNode.children] instead.
         */
        fun detach()
    }

    /**
     * A [Node] whose children are loaded lazily through a [LazyLoadableList]
     * and can be unloaded again to free memory.
     */
    interface LazyLoadableNode<T> : Node<T> {
        override val children: LazyLoadableList<Node<T>>
    }

    /**
     * A read-only lazy loadable view of [source] node,
     * obtained as a result of applying some transformation to it
     * (for instance, lazy copy, rewrite rule, etc.).
     *
     * [ViewNode] keeps a reference to the [source] node,
     * allowing to recursively unload its children when the view is no longer needed.
     *
     * NOTE: view updates over mutable nodes are not yet supported!
     *   If a mutable [source] node is changed after the construction of the view,
     *   the view will not be updated automatically.
     */
    interface ViewNode<T> : LazyLoadableNode<T> {
        val source: Node<T>
    }
}

/**
 * A mutable tree of values of type [T], possibly empty.
 *
 * Unlike read-only [Tree], this tree is mutable and can be mutated in-place.
 */
interface MutableTree<T> : Tree<T> {
    override val root: Tree.MutableNode<T>?
}

/**
 * Creates a [Tree] with the given [root].
 *
 * @param root the root node, or `null` for an empty tree.
 * @throws IllegalArgumentException if [root] has a parent.
 */
fun <T> Tree(root: Tree.Node<T>?): Tree<T> = TreeImpl(root)

/**
 * Creates a [MutableTree] with the given [root].
 *
 * @param root the root node, or `null` for an empty tree.
 * @throws IllegalArgumentException if [root] has a parent.
 */
fun <T> MutableTree(root: Tree.MutableNode<T>?): MutableTree<T> = MutableTreeImpl(root)

/**
 * Checks parent-child link consistency of the whole tree.
 *
 * @throws IllegalStateException if some node's child does not point back to it as its parent.
 */
fun <T> Tree<T>.validate() {
    root?.validate()
}

/**
 * Checks parent-child link consistency of this subtree.
 *
 * @throws IllegalStateException if some node's child does not point back to it as its parent.
 */
fun <T> Tree.Node<T>.validate() {
    check(children.all { it.parent == this }) {
        "Parent-child references are not consistent"
    }
    children.forEach { it.validate() }
}

/**
 * Unloads the node's lazily loaded children if it is a [Tree.LazyLoadableNode]; no-op otherwise.
 *
 * @param unloadViewSources If `true`, unloads the view sources of [Tree.ViewNode]s.
 */
fun Tree.Node<*>.unloadChildren(unloadViewSources: Boolean = false) {
    (this as? Tree.LazyLoadableNode<*>)?.children?.unloadAll()
    if (unloadViewSources && this is Tree.ViewNode<*>) {
        source.unloadChildren(unloadViewSources = true)
    }
}


// ========================================================
//   Tree implementation
// ========================================================

private open class TreeImpl<T>(override val root: Tree.Node<T>?) : Tree<T> {
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

private class MutableTreeImpl<T>(override val root: Tree.MutableNode<T>?) : TreeImpl<T>(root), MutableTree<T>


// ========================================================
//   Node implementations
// ========================================================

/**
 * An abstract implementation of the [Tree.Node] interface,
 * which defines the basic structure for tree nodes including data, parent, and children.
 *
 * This class provides common functionality such as structural equality checks,
 * structural hash code computation, and string representation.
 */
abstract class AbstractNode<T>(
    override val data: T,
    override val parent: Tree.Node<T>? = null,
) : Tree.Node<T> {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Tree.Node<*>) return false
        return (data == other.data && children == other.children)
    }

    override fun hashCode(): Int {
        var hash = data?.hashCode() ?: 0
        if (children.isEmpty()) return hash
        hash = 31 * hash + children.hashCode()
        return hash
    }

    override fun toString(): String {
        fun helper(padding: String, node: Tree.Node<T>): String {
            if (node.children.isEmpty()) {
                return "${padding}node(data=${node.data})"
            }
            return "${padding}node(data=${node.data}) {\n" +
                    node.children.joinToString(separator = "\n") { helper("$padding  ", it) } +
                    "\n$padding}"
        }
        return helper("", this)
    }
}

private class LeafNodeImpl<T>(
    data: T,
    parent: Tree.Node<T>? = null,
) : AbstractNode<T>(data, parent) {
    override val children: List<Tree.Node<T>> = emptyList()
}

private class ImmutableNodeImpl<T>(
    data: T,
    parent: Tree.Node<T>? = null,
    childrenSize: Int = 0,
    child: (Tree.Node<T>, Int) -> Tree.Node<T>
) : AbstractNode<T>(data, parent) {
    override val children = List<Tree.Node<T>>(childrenSize) { i -> child(this, i) }
}

private class MutableNodeImpl<T>(
    override var data: T,
    parent: Tree.MutableNode<T>? = null,
) : AbstractNode<T>(data, parent), Tree.MutableNode<T> {

    override var parent: Tree.MutableNode<T>? = parent
        private set

    override val children: MutableList<Tree.MutableNode<T>> = ObservableList(
        onSet = { _, old, new ->
            old.detach()
            new.attach(this)
        },
        onAdd = { it.attach(this) },
        onRemove = { it.detach() },
        onClear = { elements -> elements.forEach { it.detach() } },
    )

    override fun attach(newParent: Tree.MutableNode<T>) {
        check(parent == null) {
            "Node is already attached"
        }
        parent = newParent
    }

    override fun detach() {
        parent = null
    }
}

private class LazyLoadableNodeImpl<T>(
    data: T,
    parent: Tree.Node<T>? = null,
    override val children: LazyLoadableList<Tree.Node<T>>,
) : AbstractNode<T>(data, parent), Tree.LazyLoadableNode<T>

private class LazyCopyNodeImpl<T>(
    override val data: T,
    override val parent: Tree.Node<T>?,
    override val source: Tree.Node<T>,
) : Tree.ViewNode<T> {
    override val children: LazyLoadableList<Tree.Node<T>> = LazyLoadableList(
        loadAll = { source.children.map { it.lazyCopy(this) } },
    )
}

// ========================================================
//   Tree and Node building DSL
// ========================================================

@DslMarker
annotation class TreeDslMarker

/**
 * A node builder DSL - the receiver of the [tree] and [node] DSL blocks.
 *
 * Each [node] call adds a child to the enclosing node, e.g.:
 * ```
 * val tree: Tree<Int> = tree {
 *     node(1) {
 *         node(2) {
 *             node(4)
 *             node(5)
 *         }
 *         node(3)
 *     }
 * }
 * ```
 * builds the tree `1(2(4,5),3)`.
 *
 * The builder object collects the children of one node and upon a call to [build]
 * constructs a new node with the given data and the collected children.
 */
@TreeDslMarker
interface NodeBuilder<T> {

    /**
     * Adds a node with [data] and the children declared in [block] as the next child of this builder's node.
     */
    fun node(data: T, block: NodeBuilder<T>.() -> Unit = {})

    /**
     * Adds a (deep) copy of the given [node] as the next child of this builder's node.
     */
    fun node(node: Tree.Node<T>)

    /**
     * Adds a lazy node with [data] and the children declared in [block] as the next child of this builder's node.
     * The children of the new node are loaded lazily when they are accessed.
     */
    fun lazyNode(data: T, block: NodeBuilder<T>.() -> Unit)

    /**
     * Adds a lazy copy of the specified [node] as the next child of this builder's node.
     * The children of the new node are copied lazily when they are accessed.
     *
     * @param node The tree node to be lazily added as a child.
     */
    fun lazyNode(node: Tree.Node<T>)

    /**
     * Builds a node with [data] and the children collected by this builder.
     */
    fun build(data: T): Tree.Node<T>

    /**
     * Builds a tree with the children collected by this builder.
     *
     * The builder should have at most one node added - this node will become the root of the tree.
     */
    fun buildTree(): Tree<T>
}

private class NodeBuilderImpl<T>(
    private val parent: Tree.Node<T>? = null,
    private val lazy: Boolean = false,
) : NodeBuilder<T> {
    private val _children = mutableListOf<(Tree.Node<T>?) -> Tree.Node<T>>()

    override fun node(data: T, block: NodeBuilder<T>.() -> Unit) {
        _children.add { parent ->
            NodeBuilderImpl(parent).apply(block).build(data)
        }
    }

    override fun node(node: Tree.Node<T>) {
        _children.add { parent ->
            node.copy(parent)
        }
    }

    override fun lazyNode(data: T, block: NodeBuilder<T>.() -> Unit) {
        _children.add { parent ->
            NodeBuilderImpl(parent, lazy = true).apply(block).build(data)
        }
    }

    override fun lazyNode(node: Tree.Node<T>) {
        _children.add { parent ->
            node.lazyCopy(parent)
        }
    }

    override fun build(data: T): Tree.Node<T> =
        if (lazy) {
            LazyLoadableNodeImpl(data, parent,
                LazyLoadableList(size = _children.size, load = { i -> _children[i](parent) })
            )
        } else {
            ImmutableNodeImpl(data, parent, _children.size) { parent, i -> _children[i](parent) }
        }

    override fun buildTree(): Tree<T> {
        check(parent == null) { "Root node must not have a parent" }
        check(_children.size <= 1) { "Tree must have a single root node" }
        if (_children.isEmpty()) return Tree(null)
        return Tree(_children[0](null))
    }
}

/**
 * A mutable node builder DSL - the receiver of the [mutableTree] and [mutableNode] DSL blocks.
 * A mutable counterpart of [NodeBuilder].
 *
 * Each [node] call adds a child to the enclosing node, e.g.:
 * ```
 * val tree: Tree<Int> = mutableTree {
 *     node(1) {
 *         node(2) {
 *             node(4)
 *             node(5)
 *         }
 *         node(3)
 *     }
 * }
 * ```
 * builds the tree `1(2(4,5),3)`.
 *
 * @see NodeBuilder
 */
@TreeDslMarker
interface MutableNodeBuilder<T> {

    /**
     * Adds a mutable node with [data] and the children declared in [block] as the next child of this builder's node.
     */
    fun node(data: T, block: MutableNodeBuilder<T>.() -> Unit = {}): Tree.MutableNode<T>

    /**
     * Adds a (deep) copy of the given [node] as the next child of this builder's node.
     */
    fun node(node: Tree.Node<T>): Tree.MutableNode<T>

    /**
     * Builds a node with [data] and the children collected by this builder.
     */
    fun build(data: T): Tree.MutableNode<T>

    /**
     * Builds a tree with the children collected by this builder.
     *
     * The builder should have at most one node added - this node will become the root of the tree.
     */
    fun buildTree(): MutableTree<T>
}

private class MutableNodeBuilderImpl<T> : MutableNodeBuilder<T> {
    private val _children = mutableListOf<Tree.MutableNode<T>>()

    override fun node(data: T, block: MutableNodeBuilder<T>.() -> Unit): Tree.MutableNode<T> {
        val builder = MutableNodeBuilderImpl<T>().apply(block)
        return builder.build(data).also {
            node -> _children.add(node)
        }
    }

    override fun node(node: Tree.Node<T>): Tree.MutableNode<T> {
        return node.mutableCopy().also {
            _children.add(it)
        }
    }

    override fun build(data: T): Tree.MutableNode<T> =
        mutableNode(data).also { node ->
            node.children.addAll(_children)
        }

    override fun buildTree(): MutableTree<T> {
        check(_children.size <= 1) { "Tree must have a single root node" }
        if (_children.isEmpty()) return MutableTree(null)
        return MutableTree(_children[0])
    }
}

/**
 * Creates a new immutable leaf node with given [data].
 *
 * The node is in a detached state, meaning its parent is `null`.
 */
fun <T> node(data: T): Tree.Node<T> = LeafNodeImpl(data)

/**
 * Builds a new node with given [data] and the children declared in [block] (see [NodeBuilder]).
 *
 * The node is in a detached state, meaning its parent is `null`.
 */
fun <T> node(data: T, block: NodeBuilder<T>.() -> Unit): Tree.Node<T> {
    val builder = NodeBuilderImpl<T>().apply(block)
    return builder.build(data)
}

/**
 * Builds a new lazy-loadable node with given [data] and the children declared in [block] (see [NodeBuilder]).
 *
 * The node is in a detached state, meaning its parent is `null`.
 */
fun <T> lazyNode(data: T, block: NodeBuilder<T>.() -> Unit): Tree.LazyLoadableNode<T> {
    val builder = NodeBuilderImpl<T>(lazy = true).apply(block)
    return builder.build(data) as Tree.LazyLoadableNode<T>
}

/**
 * Builds a [Tree] whose root, if any, is the single node declared in [block] (see [NodeBuilder]).
 *
 * @throws IllegalArgumentException if [block] declares more than one root node.
 */
fun <T> tree(block: NodeBuilder<T>.() -> Unit): Tree<T> {
    return NodeBuilderImpl<T>().apply(block).buildTree()
}

/**
 * Creates a new mutable leaf node with given [data].
 *
 * The new node is detached - its parent node is `null`.
 * Nodes added to its children list are relinked to it automatically.
 */
fun <T> mutableNode(data: T): Tree.MutableNode<T> = MutableNodeImpl(data)

/**
 * Creates a new mutable node with the given [data]
 * and attaches [children] to it ([children] must be detached).
 *
 * The new node is detached - its parent node is `null`.
 * Nodes added to its children list are relinked to it automatically.
 */
fun <T> mutableNode(data: T, children: List<Tree.MutableNode<T>>): Tree.MutableNode<T> {
    require(children.all { it.parent == null }) { "All children must be detached" }
    return MutableNodeImpl(data).also { node ->
        node.children.addAll(children)
    }
}

/**
 * Builds a new node with given [data] and the children declared in [block] (see [NodeBuilder]).
 *
 * The new node is detached - its parent node is `null`.
 */
fun <T> mutableNode(data: T, block: MutableNodeBuilder<T>.() -> Unit): Tree.MutableNode<T> {
    val builder = MutableNodeBuilderImpl<T>().apply(block)
    return builder.build(data)
}

/**
 * Builds a [Tree] whose root, if any, is the single node declared in [block] (see [NodeBuilder]).
 *
 * @throws IllegalArgumentException if [block] declares more than one root node.
 */
fun <T> mutableTree(block: MutableNodeBuilder<T>.() -> Unit): MutableTree<T> {
    return MutableNodeBuilderImpl<T>().apply(block).buildTree()
}


// ========================================================
//   Copy
// ========================================================

/**
 * Creates a new immutable copy of the tree.
 */
fun <T> Tree<T>.copy(): Tree<T> = TreeImpl(root?.copy())

/**
 * Creates a new immutable copy of the node and its entire children subtree.
 * The new node is in a detached state, meaning its parent is null.
 */
fun <T> Tree.Node<T>.copy(): Tree.Node<T> = this.copy(parent = null)

private fun <T> Tree.Node<T>.copy(parent: Tree.Node<T>?): Tree.Node<T> =
    ImmutableNodeImpl(data, parent, children.size) { parent, i -> children[i].copy(parent) }

/**
 * Creates a new lazy copy of this tree.
 *
 * @see Tree.Node.lazyCopy
 */
fun <T> Tree<T>.lazyCopy(): Tree<T> = Tree(root?.lazyCopy())

/**
 * Creates a new lazy copy of the node.
 * The children nodes are copied lazily on demand upon first access.
 */
fun <T> Tree.Node<T>.lazyCopy(): Tree.Node<T> = lazyCopy(null)

private fun <T> Tree.Node<T>.lazyCopy(parent: Tree.Node<T>?): Tree.Node<T> =
    LazyCopyNodeImpl(data, parent, this)

/**
 * Creates a new mutable copy of the tree.
 */
fun <T> Tree<T>.mutableCopy(): MutableTree<T> = MutableTreeImpl(root?.mutableCopy())

/**
 * Creates a new mutable copy of the node and its entire children subtree.
 * The new node is in a detached state, meaning its parent is null.
 */
fun <T> Tree.Node<T>.mutableCopy(): Tree.MutableNode<T> =
    MutableNodeImpl(data).also { node ->
        node.children.addAll(children.map { it.mutableCopy() })
    }

// ========================================================
//   ForEach
// ========================================================

/**
 * Traverses the tree in depth-first order and applies [action] to each node's data on entering.
 *
 * @param action the action to apply to each node's data.
 */
fun <T> Tree<T>.forEach(action: (T) -> Unit) {
    root?.forEach(action)
}

/**
 * Traverses this subtree in depth-first order and applies [action] to each node's data on entering.
 *
 * @param action the action to apply to each node's data.
 */
fun <T> Tree.Node<T>.forEach(action: (T) -> Unit) {
    action(data)
    children.forEach { it.forEach(action) }
}

/**
 * Traverses the tree in depth-first order and applies [action] to each node on entering.
 *
 * @param action the action to apply to each node.
 */
fun <T> Tree<T>.forEachNode(action: (Tree.Node<T>) -> Unit) {
    root?.forEachNode(action)
}

// TODO: make a lazy version which unloads on node exit?
/**
 * Traverses this subtree in depth-first order and applies [action] to each node on entering.
 *
 * @param action the action to apply to each node.
 */
fun <T> Tree.Node<T>.forEachNode(action: (Tree.Node<T>) -> Unit) {
    action(this)
    children.forEach { it.forEachNode(action) }
}

// ========================================================
//   Map
// ========================================================

/**
 * Maps the tree into a new tree via provided [transform] function.
 *
 * @see Tree.Node.map
 */
fun <T, U> Tree<T>.map(transform: (T) -> U): Tree<U> =
    Tree(root?.map(transform))

/**
 * Returns a new node of the same structure as this node's subtree
 * with each node's data mapped through [transform].
 *
 * The created node is in detached state, meaning its parent is `null`.
 *
 * @param transform maps a node's data to the new value.
 * @return the new mapped tree; the original tree is not modified.
 */
fun <T, U> Tree.Node<T>.map(transform: (T) -> U): Tree.MutableNode<U> {
    return mutableNode(transform(data), children.map { it.map(transform) })
}

// ========================================================
//   Filter
// ========================================================

/**
 * Filter the tree by removing all nodes that do not satisfy the given [predicate].
 *
 * @see Tree.Node.filter
 */
fun <T> Tree<T>.filter(predicate: (T) -> Boolean): Tree<T> =
    Tree(root?.filter(predicate))

/**
 * Returns a new node containing only the subtree nodes that satisfy the given [predicate].
 *
 * Nodes are filtered recursively, and a node is kept
 * if its data matches the [predicate] or if any of its descendants is kept.
 *
 * The created node is in detached state, meaning its parent is `null`.
 *
 * @param predicate tested against each node's data.
 * @return the new filtered tree; empty if no node matches.
 */
fun <T> Tree.Node<T>.filter(predicate: (T) -> Boolean): Tree.MutableNode<T>? {
    val children = this.children.mapNotNull { child -> child.filter(predicate) }
    if (predicate(data) || children.isNotEmpty()) {
        return mutableNode(data, children)
    }
    return null
}

// ========================================================
//   Transform
// ========================================================

/**
 * Transforms the given tree using the provided [transform] function.
 *
 * @see Tree.Node.transform
 */
fun <T> Tree<T>.transform(transform: (Tree.Node<T>) -> Tree.Node<T>): Tree<T> =
    Tree(root?.transform(transform))

/**
 * Returns a new node built by replacing each node of the given node's subtree with the result of [transform].
 * The transformation is performed top-down:
 * the replacement's data is taken as is, and its children are then transformed recursively.
 *
 * The created node is in detached state, meaning its parent is `null`.
 *
 * @param transform maps a node to its replacement; may change both the data and the children.
 * @return the new transformed tree.
 */
fun <T> Tree.Node<T>.transform(transform: (Tree.Node<T>) -> Tree.Node<T>): Tree.MutableNode<T> {
    val transformedNode = transform(this)
    return MutableNodeImpl(transformedNode.data).also { node ->
        node.children.addAll(transformedNode.children.map { it.transform(transform) })
    }
}

// ========================================================
//   Find
// ========================================================

/**
 * Finds the first node's data in the tree in depth-first order that satisfies the given [predicate].
 *
 * @param predicate tested against each node's data.
 * @return the first matching data, or `null` if no node matches.
 */
fun <T> Tree<T>.find(predicate: (T) -> Boolean): T? {
    return root?.find(predicate)
}

/**
 * Finds the first node's data in this subtree in depth-first order that satisfies the given [predicate].
 *
 * @param predicate tested against each node's data.
 * @return the first matching data, or `null` if no node matches.
 */
fun <T> Tree.Node<T>.find(predicate: (T) -> Boolean): T? {
    if (predicate(data)) return data
    return children.firstNotNullOfOrNull { it.find(predicate) }
}

/**
 * Finds the first node in the tree in depth-first order that satisfies the given [predicate].
 *
 * @param predicate tested against each node.
 * @return the first matching node, or `null` if no node matches.
 */
fun <T> Tree<T>.findNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? {
    return root?.findNode(predicate)
}

/**
 * Finds the first node in this subtree in depth-first order that satisfies the given [predicate].
 *
 * @param predicate tested against each node.
 * @return the first matching node, or `null` if no node matches.
 */
fun <T> Tree.Node<T>.findNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? {
    if (predicate(this)) return this
    return children.firstNotNullOfOrNull { it.findNode(predicate) }
}

/**
 * Finds the last node's data in the tree in depth-first order that satisfies the given [predicate].
 *
 * @param predicate tested against each node's data.
 * @return the last matching data, or `null` if no node matches.
 */
fun <T> Tree<T>.findLast(predicate: (T) -> Boolean): T? {
    return root?.findLast(predicate)
}

/**
 * Finds the last node's data in this subtree in depth-first order that satisfies the given [predicate].
 *
 * @param predicate tested against each node's data.
 * @return the last matching data, or `null` if no node matches.
 */
fun <T> Tree.Node<T>.findLast(predicate: (T) -> Boolean): T? {
    val found = children.lastNotNullOfOrNull { it.findLast(predicate) }
    if (found != null) return found
    if (predicate(data)) return data
    return null
}

/**
 * Finds the last node in the tree in depth-first order that satisfies the given [predicate].
 *
 * @param predicate tested against each node.
 * @return the last matching node, or `null` if no node matches.
 */
fun <T> Tree<T>.findLastNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? {
    return root?.findLastNode(predicate)
}

/**
 * Finds the last node in this subtree in depth-first order that satisfies the given [predicate].
 *
 * @param predicate tested against each node.
 * @return the last matching node, or `null` if no node matches.
 */
fun <T> Tree.Node<T>.findLastNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? {
    val found = children.lastNotNullOfOrNull { it.findLastNode(predicate) }
    if (found != null) return found
    if (predicate(this)) return this
    return null
}

// ========================================================
//   Leaf (first/last)
// ========================================================

/**
 * Returns the first (leftmost) leaf of this subtree.
 *
 * @return the leaf reached by always following the first child, or `null` if this node is itself a leaf.
 */
fun <T> Tree.Node<T>.firstLeaf(): Tree.Node<T>? {
    if (children.isEmpty()) return null
    var currentNode = this
    while (currentNode.children.isNotEmpty()) {
        currentNode = currentNode.children.first()
    }
    return currentNode
}

/**
 * Returns the last (rightmost) leaf of this subtree.
 *
 * @return the leaf reached by always following the last child, or `null` if this node is itself a leaf.
 */
fun <T> Tree.Node<T>.lastLeaf(): Tree.Node<T>? {
    if (children.isEmpty()) return null
    var currentNode = this
    while (currentNode.children.isNotEmpty()) {
        currentNode = currentNode.children.last()
    }
    return currentNode
}

// ========================================================
//   Squash
// ========================================================

/**
 * Returns a new tree with children are squashed according to the given [relation] function.
 *
 * @see Tree.Node.squash
 */
fun <T> Tree<T>.squash(
    merge: (List<Tree.Node<T>>) -> T,
    relation: (Tree.Node<T>, Tree.Node<T>) -> Boolean,
): Tree<T> {
    return Tree(root?.squash(merge, relation))
}

/**
 * Returns a new node where, on every children level recursively,
 * groups of consecutive related nodes are squashed into single nodes (see [List.squash]).
 *
 * @param merge defines the new value for the squashed node group.
 * @param relation whether two adjacent nodes belong to the same group.
 * @return the new squashed tree.
 */
fun <T> Tree.Node<T>.squash(
    merge: (List<Tree.Node<T>>) -> T,
    relation: (Tree.Node<T>, Tree.Node<T>) -> Boolean,
): Tree.MutableNode<T> {
    val children = children
        .map { it.squash(merge, relation) }
        .squash(relation)
        .mapNotNull { group ->
            when (group.size) {
                0 -> null
                1 -> group.first()
                else -> {
                    val data = merge(group)
                    val children = group.flatMap { it.children }
                    group.forEach { it.children.clear() }
                    mutableNode(data, children)
                }
            }
        }
    return mutableNode(data, children)
}