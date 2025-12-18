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

interface TreeCursor<T> {
    val currentNode: Tree.Node<T>

    fun walkForward(): Sequence<Tree.Node<T>>
    fun walkBackward(): Sequence<Tree.Node<T>>

    companion object {
        fun <T> atRoot(tree: Tree<T>): TreeCursor<T>? =
            TreeCursorImpl.atRoot(tree)

        fun <T> atLastLeaf(tree: Tree<T>): TreeCursor<T>? =
            TreeCursorImpl.atLastLeaf(tree)

        fun <T> at(node: Tree.Node<T>): TreeCursor<T> =
            TreeCursorImpl.at(node)
    }
}

fun <T> TreeCursor<T>.findNextNode(predicate: (Tree.Node<T>) -> Boolean): Tree.Node<T>? =
    walkForward().firstOrNull(predicate)

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
            tree.root?.lastLeaf()?.let { TreeCursorImpl(it).apply { marker = Marker.END } }

        fun <T> at(node: Tree.Node<T>): TreeCursor<T> =
            TreeCursorImpl(node)
    }
}