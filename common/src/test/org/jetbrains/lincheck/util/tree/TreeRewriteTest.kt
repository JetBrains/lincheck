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

import org.jetbrains.lincheck.util.collections.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [rewrite] over digit trees.
 *
 * Each rule models one capability of the trace postprocessor:
 * removal, N-to-M compression, insertion of synthetic nodes, and grouping.
 *
 * Source trees are built with the [tree] DSL — with [NodeBuilder.lazyNode]
 * in the tests covering the view/laziness/unload interplay.
 */
class TreeRewriteTest {

    private fun Tree<Int>.structure(): String = root?.structure() ?: "<empty>"

    private fun Tree.Node<Int>.structure(): String =
        if (children.isEmpty()) "$data"
        else "$data(${children.joinToString(",") { it.structure() }})"

    /** Removal (as `removeCoverageInstructions`): drops odd nodes together with their subtrees. */
    private val removeOdd = TreeRewriteRule<Int> { node ->
        node.takeIf { it.data % 2 == 0 }
    }

    /**
     * Compression (as `compressDefaultPairs`): collapses single-child chains into one node,
     * gluing digits (`1 -> 2 -> 3` becomes `123`) and adopting the innermost node's children.
     */
    private val collapseChains = TreeRewriteRule<Int> { node ->
        var current = node
        var acc = node.data
        while (current.children.size == 1) {
            current = current.children.single()
            acc = acc * 10 + current.data
        }
        node(acc) { current.children.forEach { lazyNode(it) } }
    }

    /** Insertion: puts a synthetic `0` node before every child (skips the root window). */
    private val insertZeroBeforeEach = TreeRewriteRule.singleMultiNode { node ->
        // do not rewrite root to preserve single-root invariant
        if (node.parent == null) listOf(node) else listOf(node(0), node)
    }

    /**
     * Grouping (as `squash`): runs of 2+ consecutive even children go under a synthetic node
     * holding the negated sum of the run.
     * Group nodes are marked by negative data, and their windows are skipped —
     * otherwise the rule would regroup its own output on every level, looping forever.
     */
    private val groupEvenRuns = TreeRewriteRule.multiNode<Int> { nodes ->
        // skip already grouped nodes to avoid infinite recursion
        val parent = nodes.firstOrNull()?.parent
        if ((parent?.data ?: 0) < 0) return@multiNode nodes

        nodes
            .squash { a, b -> (a.data % 2 == 0) && (b.data % 2 == 0) }
            .map { nodes ->
                if (nodes.size <= 1) nodes.single()
                else node(-nodes.sumOf { it.data }) { nodes.forEach { lazyNode(it) } }
            }
    }

    @Test
    fun `view with no rules mirrors the source tree`() {
        val source = tree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(3)
            }
        }

        val view = source.rewrite(emptyList())

        assertEquals("1(2(4,5),3)", view.structure())
        view.validate()
    }

    @Test
    fun `rewriting the root into multiple nodes fails`() {
        val source = tree {
            node(1)
        }
        val duplicate = TreeRewriteRule.singleMultiNode<Int> { node ->
            listOf(node, node)
        }

        val view = source.rewrite(duplicate)

        assertThrows(IllegalStateException::class.java) { view.root }
    }

    @Test
    fun `removal rule drops nodes together with their subtrees`() {
        val source = tree {
            node(2) {
                node(4) {
                    node(1)
                    node(6)
                }
                node(3) {
                    node(8) // dropped with its odd parent
                }
                node(5)
            }
        }

        val view = source.rewrite(removeOdd)

        assertEquals("2(4(6))", view.structure())
    }

    @Test
    fun `removal rule can drop the root`() {
        val source = tree {
            node(1) {
                node(2)
            }
        }

        val view = source.rewrite(removeOdd)

        assertNull(view.root)
        assertEquals("<empty>", view.structure())
    }

    @Test
    fun `compression rule collapses single-child chains including the root`() {
        val chain = tree {
            node(1) {
                node(2) {
                    node(3) {
                        node(4)
                        node(5)
                    }
                }
            }
        }
        assertEquals("123(4,5)", chain.rewrite(collapseChains).structure())

        val branchy = tree {
            node(1) {
                node(2) {
                    node(3)
                }
                node(4)
            }
        }
        assertEquals("1(23,4)", branchy.rewrite(collapseChains).structure())
    }

    @Test
    fun `insertion rule adds synthetic nodes into every window`() {
        val source = tree {
            node(1) {
                node(2)
                node(3) {
                    node(4)
                }
            }
        }

        val view = source.rewrite(insertZeroBeforeEach)

        assertEquals("1(0,2,0,3(0,4))", view.structure())
    }

    @Test
    fun `grouping rule squashes runs of children under a synthetic parent`() {
        val source = tree {
            node(9) {
                node(2) {
                    node(7) // grouped nodes keep their subtrees
                }
                node(4)
                node(1)
                node(6)
                node(8)
            }
        }

        val view = source.rewrite(groupEvenRuns)

        assertEquals("9(-6(2(7),4),1,-14(6,8))", view.structure())
        view.validate()
    }

    @Test
    fun `rules apply in order within each window`() {
        val source = tree {
            node(2) {
                node(1)
                node(4)
            }
        }

        val removeThenInsert = source.rewrite(listOf(removeOdd, insertZeroBeforeEach))
        assertEquals("2(0,4)", removeThenInsert.structure())

        val removeThenInsertSeq = source.rewrite(removeOdd).rewrite(insertZeroBeforeEach)
        assertEquals(removeThenInsert.structure(), removeThenInsertSeq.structure())

        // Inserted zeros are even, so the reversed order keeps them while dropping the odd node.
        val insertThenRemove = source.rewrite(listOf(insertZeroBeforeEach, removeOdd))
        assertEquals("2(0,0,4)", insertThenRemove.structure())

        val insertThenRemoveSeq = source.rewrite(insertZeroBeforeEach).rewrite(removeOdd)
        assertEquals(insertThenRemove.structure(), insertThenRemoveSeq.structure())

    }

    @Test
    fun `stacked views hide nodes removed by earlier stages`() {
        val source = tree {
            node(2) {
                node(4) {
                    node(6)
                }
                node(3) {
                    node(8)
                }
                node(10)
            }
        }
        val seen = mutableListOf<Int>()
        val recordData = TreeRewriteRule<Int> {
            it.also { seen.add(it.data) }
        }

        val stacked = source.rewrite(removeOdd).rewrite(recordData)
        assertEquals("2(4(6),10)", stacked.structure())

        assertFalse(seen.contains(3))
        assertEquals(listOf(2, 4, 10, 6), seen)
    }

    @Test
    fun `windows are rewritten lazily and cached`() {
        var calls = 0
        val counting = TreeRewriteRule<Int> {
            it.also { calls++ }
        }
        val source = tree {
            node(1) {
                node(2) {
                    node(3)
                }
            }
        }

        val view = source.rewrite(counting)
        assertEquals(0, calls)

        val root = view.root!!
        assertEquals(1, calls) // root window only

        val child = root.children.single()
        assertEquals(2, calls)

        assertSame(child, root.children.single()) // cached, not re-rewritten
        assertEquals(2, calls)

        child.children.single()
        assertEquals(3, calls)
    }

    @Test
    fun `unloadChildren drops the cache and the window is rewritten anew`() {
        var calls = 0
        val counting = TreeRewriteRule<Int> {
            it.also { calls++ }
        }
        val source = tree {
            node(1) {
                node(2)
            }
        }

        val root = source.rewrite(counting).root!!
        val childBefore = root.children.single()
        assertEquals(2, calls)

        root.unloadChildren()

        val childAfter = root.children.single()
        assertEquals(3, calls)
        assertNotSame(childBefore, childAfter)
        assertEquals(2, childAfter.data)
    }

    @Test
    fun `parent pointers are consistent in the rewritten view`() {
        val source = tree {
            node(9) {
                node(2)
                node(4)
                node(1) {
                    node(3) {
                        node(5)
                    }
                }
            }
        }

        val view = source.rewrite(listOf(groupEvenRuns, collapseChains))

        assertEquals("9(-6(2,4),135)", view.structure())
        assertNull(view.root!!.parent)
        view.validate()
        view.forEachNode { node ->
            node.children.forEach { child -> assertSame(node, child.parent) }
        }
    }

    @Test
    fun `source tree is not modified by rewriting`() {
        val source = tree {
            node(8) {
                node(2) {
                    node(1)
                }
                node(4)
                node(3) {
                    node(6)
                }
            }
        }
        val before = source.structure()

        val view = source.rewrite(listOf(removeOdd, groupEvenRuns, collapseChains))
        view.forEachNode { node -> node.children.forEach { _ -> } } // traverse the whole view

        assertEquals(before, source.structure())
        source.validate()
    }

    @Test
    fun `subtree of a filtered-out lazy node is never materialized`() {
        val source = tree {
            lazyNode(2) {
                lazyNode(4) {
                    node(6)
                }
                lazyNode(3) {
                    lazyNode(8) {
                        node(10)
                    }
                }
                node(8)
            }
        }

        val view = source.rewrite(removeOdd)
        view.forEachNode { } // traverse the whole view

        // Rewriting the root window materializes node `3` itself (the rule reads its data),
        // but the node is dropped before its own children window is ever touched.
        val dropped = source.root!!.children[1] as Tree.LazyLoadableNode<*>
        assertEquals(3, dropped.data)
        assertEquals(1, dropped.children.size)
        assertFalse(dropped.children.isLoaded(0))
    }

    @Test
    fun `unloadChildren forwards to the lazy source node and the filter is re-applied on reload`() {
        val source = tree {
            lazyNode(2) {
                lazyNode(4) {
                    node(6)
                }
                node(3)
                node(8)
            }
        }
        val sourceChildren = source.root!!.children as LazyLoadableList<*>

        val viewRoot = source.rewrite(removeOdd).root!!
        val childrenBefore = viewRoot.children
        assertEquals(listOf(4, 8), childrenBefore.map { it.data })
        val childBefore = childrenBefore[0]
        assertTrue(sourceChildren.isLoaded(0))

        viewRoot.unloadChildren(unloadViewSources = true)
        assertFalse(sourceChildren.isLoaded(0))

        val childrenAfter = viewRoot.children
        assertEquals(listOf(4, 8), childrenAfter.map { it.data })
        assertNotSame(childBefore, childrenAfter[0])
    }
}