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

import org.jetbrains.lincheck.util.collections.LazyLoadableList

/**
 * A rule rewriting one node's children list into a new list
 *
 * Rewrite rules allow transforming a tree into another tree
 * by applying a function to a children list of each node in the tree.
 * Rules are applied lazily in top-down order.
 *
 * A rule may:
 *   - keep the children list unchanged,
 *   - drop nodes (together with their subtrees),
 *   - replace N adjacent nodes with M new ones,
 *   - group nodes under synthetic parents,
 *   - insert nodes built from scratch.
 *
 * Rules must not mutate input nodes (in particular, must not relink them);
 * parent pointers in the rewritten tree view are managed internally by the view itself.
 *
 * To apply a rule to a tree use [Tree.rewrite].
 */
interface TreeRewriteRule<T> {
    /**
     * Rewrites the list [nodes] into another list of [nodes].
     */
    fun rewrite(nodes: List<Tree.Node<T>>): List<Tree.Node<T>>

    companion object {
        inline fun <T> singleNode(crossinline rule: (Tree.Node<T>) -> Tree.Node<T>?): TreeRewriteRule<T> =
            multiNode { nodes -> nodes.flatMap { listOfNotNull(rule(it)) } }

        inline fun <T> singleMultiNode(crossinline rule: (Tree.Node<T>) -> List<Tree.Node<T>>): TreeRewriteRule<T> =
            multiNode { nodes -> nodes.flatMap { rule(it) } }

        inline fun <T> multiNode(crossinline rule: (List<Tree.Node<T>>) -> List<Tree.Node<T>>): TreeRewriteRule<T> =
            object : TreeRewriteRule<T> {
                override fun rewrite(nodes: List<Tree.Node<T>>) = rule(nodes)
            }
    }
}

inline fun <T> TreeRewriteRule(crossinline rule: (Tree.Node<T>) -> Tree.Node<T>?): TreeRewriteRule<T> =
    TreeRewriteRule.singleNode { rule(it) }

/**
 * Composes a list of rewrite rules into a single rule.
 * The resulting rule applies the given rules in left-to-right order.
 */
fun <T> List<TreeRewriteRule<T>>.compose(): TreeRewriteRule<T> {
    val rules = this
    return TreeRewriteRule.multiNode { nodes ->
        rules.fold(nodes) { nodes, rule ->
            rule.rewrite(nodes)
        }
    }
}

/**
 * Applies given rewrite [rule] to the given tree.
 * The rule must produce at most one node for the root.
 *
 * @see Tree.Node.rewrite
 */
fun <T> Tree<T>.rewrite(rule: TreeRewriteRule<T>): Tree<T> = object : Tree<T> {
    override val root: Tree.Node<T>? by lazy {
        val rewritten = this@rewrite.root?.rewrite(parent = null, rule = rule)
            ?: return@lazy null
        check(rewritten.size <= 1) {
            "Rewrite of the root node must produce at most one node, got ${rewritten.size}"
        }
        rewritten.singleOrNull()
    }
}

/**
 * Applies given rewrite [rules] to the given tree in left-to-right order.
 * The rules must produce at most one node for the root.
 *
 * @see Tree.rewrite
 */
fun <T> Tree<T>.rewrite(rules: List<TreeRewriteRule<T>>): Tree<T> =
    rewrite(rules.compose())

/**
 * Applies given rewrite [rule] to the given node's subtree.
 *
 * Creates a read-only view of the tree with [rule] applied to every node lazily in top-down order.
 * The new node is in detached state, meaning its parent is `null`.
 *
 * The created node and all its children (recursively) are lazy loadable nodes ([Tree.LazyLoadableNode]).
 * Each node's children window is rewritten on first access and cached;
 * [Tree.LazyLoadableNode.unloadChildren] drops the cache,
 * so the window is rewritten anew on the next access.
 * Rewriting the same window again must produce the same number of nodes,
 * which hold for deterministic rules over a stable source tree.
 */
fun <T> Tree.Node<T>.rewrite(rule: TreeRewriteRule<T>): List<Tree.LazyLoadableNode<T>> =
    rewrite(parent = null, rule = rule)

/**
 * Applies given rewrite [rules] to the given node's subtree in left-to-right order.
 *
 * @see Tree.Node.rewrite
 */
fun <T> Tree.Node<T>.rewrite(rules: List<TreeRewriteRule<T>>): List<Tree.LazyLoadableNode<T>> =
    rewrite(rules.compose())

private fun <T> Tree.Node<T>.rewrite(parent: Tree.Node<T>?, rule: TreeRewriteRule<T>): List<Tree.LazyLoadableNode<T>> {
    return rule.rewrite(listOf(this)).map { RewritingNode(it.data, parent, it.children, source = it, rule) }
}

private class RewritingNode<T>(
    data: T,
    parent: Tree.Node<T>?,
    children: List<Tree.Node<T>>,
    override val source: Tree.Node<T>,
    val rule: TreeRewriteRule<T>,
) : AbstractNode<T>(data, parent), Tree.ViewNode<T> {
    override val children: LazyLoadableList<Tree.Node<T>> = LazyLoadableList(
        loadAll = {
            rule.rewrite(children).map { RewritingNode(it.data, parent = this, it.children, source = it, rule) }
        },
    )
}