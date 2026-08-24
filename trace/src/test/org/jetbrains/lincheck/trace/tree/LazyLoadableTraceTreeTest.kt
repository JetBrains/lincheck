/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.tree

import org.jetbrains.lincheck.trace.TRMethodCallTracePoint
import org.jetbrains.lincheck.trace.TRTracePoint
import org.jetbrains.lincheck.util.collections.LazyLoadableList
import org.jetbrains.lincheck.util.tree.Tree
import org.jetbrains.lincheck.util.tree.forEach
import org.jetbrains.lincheck.util.tree.node
import org.jetbrains.lincheck.util.tree.unloadChildren
import org.jetbrains.lincheck.util.tree.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [LazyLoadableTraceTree] / [LazyLoadableTraceNode]:
 * a small trace is built with the [TraceBuilder] DSL, saved,
 * read back with a lazy reader (see [withTraceTree]),
 * and traversed through the lazily loaded tree.
 *
 * Trace points are read shallowly:
 * they stay flat, and the tree structure lives entirely in the nodes.
 */
class LazyLoadableTraceTreeTest {

    /**
     * Whether the list's backing cache was allocated (i.e., its size was discovered).
     * Production code has no use for this, so tests reach the `LazyLoadableListImpl.cache` field reflectively.
     */
    private val LazyLoadableList<*>.isCacheInitialized: Boolean
        get() = javaClass.getDeclaredField("cache")
            .apply { isAccessible = true }
            .get(this) != null

    /**
     * Builds the following single-thread trace:
     *
     * ```
     * root
     *   a
     *     b
     *   c
     * ```
     */
    private fun TraceBuilder.testTrace(): Tree.Node<TRTracePoint> {
        fun m(name: String) = call("com.example.Foo", name)
        return node(m("root")) {
            node(m("a")) {
                node(m("b"))
            }
            node(m("c"))
        }
    }

    @Test
    fun `children are materialized lazily on access`() {
        withTraceTree(build = { testTrace() }) { _, tree ->
            val root = tree.root!!
            assertEquals("root", root.methodName)
            assertNull(root.parent)

            // Trace points carry no children at all now; flatness is structural.
            assertEquals(2, root.children.size)
            assertFalse(root.children.isLoaded(0))
            assertFalse(root.children.isLoaded(1))

            val a = root.children[0]
            assertEquals("a", a.methodName)
            assertSame(root, a.parent)
            assertTrue(root.children.isLoaded(0))
            assertFalse(root.children.isLoaded(1)) // sibling is still not loaded

            val b = a.children.single()
            assertEquals("b", b.methodName)
            assertSame(a, b.parent)

            val c = root.children[1]
            assertEquals("c", c.methodName)
            assertTrue(c.children.isEmpty())
        }
    }

    @Test
    fun `unload drops the child node and reload materializes it again`() {
        withTraceTree(build = { testTrace() }) { _, tree ->
            val root = tree.root!!

            val a = root.children[0]
            assertEquals("a", a.methodName)
            assertTrue(root.children.isLoaded(0))

            root.children.unload(0)
            assertFalse(root.children.isLoaded(0))

            val reloaded = root.children[0]
            assertEquals("a", reloaded.methodName)
            assertNotSame(a, reloaded)
        }
    }

    @Test
    fun `unloadChildren drops cached child nodes but keeps the skimmed addresses`() {
        withTraceTree(build = { testTrace() }) { _, tree ->
            val root = tree.root!!

            val childrenBefore = root.children
            assertEquals(2, childrenBefore.size)
            val a = childrenBefore[0]
            assertTrue(childrenBefore.isLoaded(0))

            root.unloadChildren(unloadViewSources = true)
            // Addresses are skimmed once per node: the children list survives, only cached nodes are dropped.
            assertSame(childrenBefore, root.children)
            assertFalse(root.children.isLoaded(0))

            val reloaded = root.children[0]
            assertEquals("a", reloaded.methodName)
            assertNotSame(a, reloaded)
        }
    }

    @Test
    fun `tree operations work over the lazily loaded tree`() {
        withTraceTree(build = { testTrace() }) { _, tree ->
            val visited = mutableListOf<String>()
            tree.forEach { visited.add((it as TRMethodCallTracePoint).methodName) }
            assertEquals(listOf("root", "a", "b", "c"), visited)

            tree.validate()
        }
    }

    @Test
    fun `reader loaders read children shallowly without mutating the parent`() {
        withTraceTree(build = { testTrace() }) { reader, tree ->
            val root = tree.root!!.data as TRMethodCallTracePoint

            val children = reader.loadAllChildren(root)
            assertEquals(listOf("a", "c"), children.map { (it as TRMethodCallTracePoint).methodName })

            val c = children[1] as TRMethodCallTracePoint
            assertTrue(reader.loadAllChildren(c).isEmpty())

            val batched = reader.readAllChildren(root)
            assertFalse(batched.isEmpty()) // answered from the index; the batch stays unloaded
            assertFalse(batched.isLoaded(0))
            assertTrue(reader.readAllChildren(c).isEmpty())

            val lazyChildren = reader.readChildren(root)
            assertEquals(2, lazyChildren.size)
            assertEquals("c", (lazyChildren[1] as TRMethodCallTracePoint).methodName)
            assertFalse(lazyChildren.isLoaded(0)) // loading one child does not load its siblings
        }
    }

    @Test
    fun `batch loading materializes the whole level at once but not grandchildren`() {
        withTraceTree(build = { testTrace() }, batchLoading = true) { _, tree ->
            val root = tree.root!!
            assertFalse(root.children.isCacheInitialized)

            val children = root.children
            assertFalse(children.isLoaded(0)) // the batch is materialized lazily, on the first element access

            val a = children[0] // the first access materializes all children in one scan
            assertTrue(children.isLoaded(0))
            assertTrue(children.isLoaded(1))
            assertEquals("a", a.methodName)
            assertSame(root, a.parent)
            assertFalse(a.children.isCacheInitialized) // grandchildren stay unloaded

            val b = a.children.single()
            assertEquals("b", b.methodName)
            assertSame(a, b.parent)
        }
    }

    @Test
    fun `batched children unload and reload as a whole`() {
        withTraceTree(build = { testTrace() }, batchLoading = true) { _, tree ->
            val root = tree.root!!
            val children = root.children
            val a = children[0]

            root.unloadChildren(unloadViewSources = true)
            assertFalse(children.isLoaded(0))
            assertFalse(children.isLoaded(1))

            val reloaded = root.children[0] // re-materializes the whole batch
            assertTrue(children.isLoaded(1))
            assertEquals("a", reloaded.methodName)
            assertNotSame(a, reloaded)
        }
    }

    @Test
    fun `children emptiness is answered without materializing the children`() {
        withTraceTree(build = { testTrace() }) { _, tree ->
            val root = tree.root!!
            assertTrue(root.children.isNotEmpty())
            // The emptiness check skims the addresses, but no child node is materialized.
            assertFalse(root.children.isLoaded(0))
            assertFalse(root.children.isLoaded(1))

            val c = root.children[1]
            assertTrue(c.children.isEmpty())
        }
    }

    @Test
    fun `batched children emptiness is answered from the index without loading the batch`() {
        withTraceTree(build = { testTrace() }, batchLoading = true) { _, tree ->
            val root = tree.root!!
            assertTrue(root.children.isNotEmpty())
            // The emptiness check comes from the reader's index; the batch stays unloaded.
            assertFalse(root.children.isLoaded(0))

            val c = root.children[1]
            assertTrue(c.children.isEmpty()) // the (empty) batch is never loaded either
        }
    }

    @Test
    fun `children size is answered without materializing the children`() {
        withTraceTree(build = { testTrace() }) { _, tree ->
            val root = tree.root!!
            assertEquals(2, root.children.size)
            // Counting skims the addresses, but no child node is materialized.
            assertFalse(root.children.isLoaded(0))
            assertFalse(root.children.isLoaded(1))

            val c = root.children[1]
            assertEquals(0, c.children.size)
        }
    }

    @Test
    fun `tree with null root trace point has null root`() {
        withTraceTree(build = { testTrace() }) { reader, _ ->
            val tree = LazyLoadableTraceTree<TRTracePoint>(reader, rootTracePoint = null)
            assertNull(tree.root)
        }
    }
}
