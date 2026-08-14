/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util.tree

import org.jetbrains.lincheck.util.ensure

/**
 * A mutable cursor over the nodes of a [Tree].
 *
 * Walking forward visits nodes in depth-first pre-order, walking backward in the reverse order.
 * The walks are lazy and advance the cursor as they are consumed:
 * abandoning a walk midway leaves the cursor at the last yielded node,
 * from where it can be resumed in either direction.
 */
interface TreeCursor<T> {
    /** The node the cursor is currently positioned at. */
    val currentNode: Tree.Node<T>

    /**
     * Walks from the current position to the end of the tree in depth-first pre-order.
     *
     * @return a lazy sequence of the visited nodes; consuming it advances the cursor.
     */
    fun walkForward(): Sequence<Tree.Node<T>>

    /**
     * Walks from the current position back to the beginning of the tree, in reverse depth-first pre-order.
     *
     * @return a lazy sequence of the visited nodes; consuming it moves the cursor back.
     */
    fun walkBackward(): Sequence<Tree.Node<T>>

    companion object {
        /**
         * Creates a cursor positioned before the root, so that a forward walk visits the whole tree.
         *
         * @return the cursor, or `null` if the [tree] is empty.
         */
        fun <T> atRoot(tree: Tree<T>): TreeCursor<T>? =
            TreeCursorImpl.atRoot(tree)

        /**
         * Creates a cursor positioned after the last leaf, so that a backward walk visits the whole tree.
         *
         * @return the cursor, or `null` if the [tree] is empty.
         */
        fun <T> atLastLeaf(tree: Tree<T>): TreeCursor<T>? =
            TreeCursorImpl.atLastLeaf(tree)

        /** Creates a cursor positioned at [node]; a forward walk starts by yielding [node] itself. */
        fun <T> at(node: Tree.Node<T>): TreeCursor<T> =
            TreeCursorImpl.at(node)
    }
}

/**
 * Advances the cursor forward to the next node satisfying [predicate].
 *
 * @param predicate tested against each visited node.
 * @return the found node, with the cursor left on it,
 *   or `null` if the walk reaches the end of the tree without a match.
 */
fun <T> TreeCursor<T>.findNextNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? =
    walkForward().firstOrNull(predicate)

/**
 * Moves the cursor backward to the previous node satisfying [predicate].
 *
 * @param predicate tested against each visited node.
 * @return the found node, with the cursor left on it,
 *   or `null` if the walk reaches the beginning of the tree without a match.
 */
fun <T> TreeCursor<T>.findPreviousNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? =
    walkBackward().firstOrNull(predicate)

private class TreeCursorImpl<T>(currentNode: Tree.Node<T>) : TreeCursor<T> {
    override var currentNode: Tree.Node<T> = currentNode
        private set

    // Special marker that is used to mark two special virtual positions:
    //   START --- before the root node;
    //   END --- after the last leaf node.
    private var marker: Marker? = null
    private enum class Marker { START, END }

    // State of the current node:
    //   PENDING --- the node is not yet processed;
    //   PROCESSING --- the node (including its children) is being processed;
    //   FINISHED --- the node has been processed.
    private var currentNodeState: CurrentNodeState = CurrentNodeState.PENDING
    private enum class CurrentNodeState { PENDING, PROCESSING, FINISHED }

    fun findNextNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? {
        return walkForward().firstOrNull(predicate)
    }

    fun findPreviousNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? {
        return walkBackward().firstOrNull(predicate)
    }

    override fun walkForward(): Sequence<Tree.Node<T>> = sequence {
        if (marker == Marker.START) { marker = null }

        while (marker != Marker.END) {
            if (currentNodeState == CurrentNodeState.PENDING) {
                currentNodeState = CurrentNodeState.PROCESSING
                yield(currentNode)
            }

            if (currentNodeState == CurrentNodeState.PROCESSING) {
                if (currentNode.children.isNotEmpty()) {
                    currentNode = currentNode.children[0]
                    currentNodeState = CurrentNodeState.PENDING
                    continue
                }
            }

            val parent = currentNode.parent
            if (parent == null) {
                marker = Marker.END
                currentNodeState = CurrentNodeState.FINISHED
                break
            }

            val currentNodePosition = parent.children
                .indexOfFirst { it === currentNode }
                .ensure { it >= 0 }

            val nextNodePosition = currentNodePosition + 1
            if (nextNodePosition < (parent.children.size)) {
                currentNode = parent.children[nextNodePosition]
                currentNodeState = CurrentNodeState.PENDING
                continue
            }

            currentNode = parent
            currentNodeState = CurrentNodeState.FINISHED
        }
    }

    override fun walkBackward(): Sequence<Tree.Node<T>> = sequence {
        if (marker == Marker.END) { marker = null }

        while (marker != Marker.START) {
            if (currentNodeState == CurrentNodeState.PENDING) {
                currentNodeState = CurrentNodeState.PROCESSING
                if (currentNode.children.isNotEmpty()) {
                    currentNode = currentNode.children.last()
                    currentNodeState = CurrentNodeState.PENDING
                }
                continue
            }

            if (currentNodeState == CurrentNodeState.PROCESSING) {
                currentNodeState = CurrentNodeState.FINISHED
                yield(currentNode)
            }

            val parent = currentNode.parent
            if (parent == null) {
                marker = Marker.START
                currentNodeState = CurrentNodeState.FINISHED
                break
            }

            val currentNodePosition = parent.children
                .indexOfFirst { it === currentNode }
                .ensure { it >= 0 }

            val prevNodePosition = currentNodePosition - 1
            if (prevNodePosition >= 0) {
                currentNode = parent.children[prevNodePosition]
                currentNodeState = CurrentNodeState.PENDING
                continue
            }

            currentNode = parent
            currentNodeState = CurrentNodeState.PROCESSING
        }
    }

    companion object {
        fun <T> atRoot(tree: Tree<T>): TreeCursor<T>? =
            tree.root?.let { TreeCursorImpl(it).apply { marker = Marker.START } }

        fun <T> atLastLeaf(tree: Tree<T>): TreeCursor<T>? =
            // a single-node tree's root is its own last leaf, but `lastLeaf()` reports `null` for a leaf node
            tree.root?.let { root -> TreeCursorImpl(root.lastLeaf() ?: root).apply { marker = Marker.END } }

        fun <T> at(node: Tree.Node<T>): TreeCursor<T> =
            TreeCursorImpl(node)
    }
}