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

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows

class TreeTest {

    @Test
    fun `tree builder with empty block builds empty tree`() {
        val tree = tree<Int> {}

        assertNull(tree.root)
    }

    @Test
    fun `tree builder with single node builds single-node tree`() {
        val tree = tree {
            node(42)
        }.apply { validate() }

        val root = tree.root!!
        assertEquals(42, root.data)
        assertNull(root.parent)
        assertEquals(listOf<Int>(), root.children.map { it.data })
    }

    @Test
    fun `tree builder with several root nodes throws`() {
        assertThrows(IllegalStateException::class.java) {
            tree {
                node(1)
                node(2)
            }
        }
    }

    @Test
    fun `tree builder builds nested structure preserving children order`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(3)
            }
        }.apply { validate() }

        val root = tree.root!!
        assertEquals(1, root.data)
        assertEquals(listOf(2, 3), root.children.map { it.data })
        assertEquals(listOf(4, 5), root.children[0].children.map { it.data })
        assertEquals(listOf<Int>(), root.children[1].children.map { it.data })
    }

    @Test
    fun `tree builder links children to their parents`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                }
            }
        }

        val root = tree.root!!
        val child = root.children.single()
        val grandChild = child.children.single()
        assertNull(root.parent)
        assertSame(root, child.parent)
        assertSame(child, grandChild.parent)
    }

    @Test
    fun `tree builder supports control flow inside building block`() {
        val tree = tree {
            node(0) {
                for (i in 1 .. 3) {
                    node(i)
                }
            }
        }.apply { validate() }

        val expected = tree {
            node(0) {
                node(1)
                node(2)
                node(3)
            }
        }
        assertEquals(expected, tree)
    }

    @Test
    fun `node builder builds detached node with children`() {
        val node = node(1) {
            node(2)
            node(3)
        }
        node.validate()

        assertNull(node.parent)
        assertEquals(listOf(2, 3), node.children.map { it.data })
        assertSame(node, node.children[0].parent)
        assertSame(node, node.children[1].parent)
    }

    @Test
    fun `node builder result equals root of equally built tree`() {
        val node = node(1) {
            node(2) {
                node(3)
            }
        }

        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                }
            }
        }
        assertEquals(tree.root, node)
    }

    @Test
    fun `mutableTree builder with empty block builds empty tree`() {
        val tree = mutableTree<Int> {}

        assertNull(tree.root)
    }

    @Test
    fun `mutableTree builder with single node builds single-node tree`() {
        val tree = mutableTree {
            node(42)
        }.apply { validate() }

        val root = tree.root!!
        assertEquals(42, root.data)
        assertNull(root.parent)
        assertEquals(listOf<Int>(), root.children.map { it.data })
    }

    @Test
    fun `mutableTree builder with several root nodes throws`() {
        assertThrows(IllegalStateException::class.java) {
            mutableTree {
                node(1)
                node(2)
            }
        }
    }

    @Test
    fun `mutableTree builder builds nested structure preserving children order`() {
        val tree = mutableTree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(3)
            }
        }.apply { validate() }

        val root = tree.root!!
        assertEquals(1, root.data)
        assertEquals(listOf(2, 3), root.children.map { it.data })
        assertEquals(listOf(4, 5), root.children[0].children.map { it.data })
        assertEquals(listOf<Int>(), root.children[1].children.map { it.data })
    }

    @Test
    fun `mutableTree builder links children to their parents`() {
        val tree = mutableTree {
            node(1) {
                node(2) {
                    node(3)
                }
            }
        }

        val root = tree.root!!
        val child = root.children.single()
        val grandChild = child.children.single()
        assertNull(root.parent)
        assertSame(root, child.parent)
        assertSame(child, grandChild.parent)
    }

    @Test
    fun `mutableTree builder returns created nodes`() {
        var a: Tree.MutableNode<Int>? = null
        var b: Tree.MutableNode<Int>? = null
        var c: Tree.MutableNode<Int>? = null
        val tree = mutableTree {
            a = node(1) {
                b = node(2)
                c = node(3)
            }
        }

        val root = tree.root!!
        assertSame(a, root)
        assertSame(b, root.children[0])
        assertSame(c, root.children[1])
    }

    @Test
    fun `mutableTree builder supports control flow inside building block`() {
        val tree = mutableTree {
            node(0) {
                for (i in 1 .. 3) {
                    node(i)
                }
            }
        }.apply { validate() }

        val expected = tree {
            node(0) {
                node(1)
                node(2)
                node(3)
            }
        }
        assertEquals(expected, tree)
    }

    @Test
    fun `mutableNode builds detached leaf node`() {
        val node = mutableNode(42)

        assertEquals(42, node.data)
        assertNull(node.parent)
        assertEquals(listOf<Int>(), node.children.map { it.data })
    }

    @Test
    fun `mutableNode builder builds detached node with children`() {
        val node = mutableNode(1) {
            node(2)
            node(3)
        }
        node.validate()

        assertNull(node.parent)
        assertEquals(listOf(2, 3), node.children.map { it.data })
        assertSame(node, node.children[0].parent)
        assertSame(node, node.children[1].parent)
    }

    @Test
    fun `nodes of built mutable tree can be modified`() {
        val tree = mutableTree {
            node(1) {
                node(2)
            }
        }

        val root = tree.root!!
        root.data = 10
        val removed = root.children.removeAt(0)
        val added = mutableNode(3)
        root.children.add(added)
        tree.validate()

        assertNull(removed.parent)
        assertSame(root, added.parent)

        val expected = tree {
            node(10) {
                node(3)
            }
        }
        assertEquals(expected, tree)
    }

    @Test
    fun `mutable and immutable builders build equal trees`() {
        val immutable = tree {
            node(1) {
                node(2) {
                    node(4)
                }
                node(3)
            }
        }
        val mutable = mutableTree {
            node(1) {
                node(2) {
                    node(4)
                }
                node(3)
            }
        }

        assertEquals(immutable, mutable)
        assertEquals(mutable, immutable)
        assertEquals(immutable.hashCode(), mutable.hashCode())
    }

    @Test
    fun `copy of empty tree is empty`() {
        val tree = tree<Int> {}
        val copy = tree.copy()

        assertNull(copy.root)
        assertEquals(tree, copy)
    }

    @Test
    fun `copy of tree is deep`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(4)
                }
                node(3)
            }
        }
        val copy = tree.copy()
            .apply { validate() }

        assertEquals(tree, copy)
        val original = tree.root!!
        val copied = copy.root!!
        assertNotSame(original, copied)
        assertNotSame(original.children[0], copied.children[0])
        assertNotSame(original.children[0].children[0], copied.children[0].children[0])
        assertNull(copied.parent)
        assertSame(copied, copied.children[1].parent)
    }

    @Test
    fun `copy of mutable tree produces immutable nodes`() {
        val tree = mutableTree {
            node(1) {
                node(2)
            }
        }

        val copy = tree.copy()
            .apply { validate() }

        assertEquals(tree, copy)
        assertFalse(copy.root is Tree.MutableNode<*>)
        assertFalse(copy.root!!.children.single() is Tree.MutableNode<*>)
    }

    @Test
    fun `copy is not affected by later modifications of the original`() {
        val tree = mutableTree {
            node(1) {
                node(2)
            }
        }

        val copy = tree.copy()
        tree.root!!.data = 10
        tree.root!!.children.add(mutableNode(3))

        val expected = tree {
            node(1) {
                node(2)
            }
        }
        assertEquals(expected, copy)
    }

    @Test
    fun `copy of node detaches it from its parent`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                }
            }
        }

        val subtree = tree.root!!.children.single()
        val copy = subtree.copy()
        copy.validate()

        assertEquals(subtree, copy)
        assertNotSame(subtree, copy)
        assertNull(copy.parent)
        assertSame(copy, copy.children.single().parent)
    }

    @Test
    fun `mutableCopy of empty tree is empty`() {
        val tree = tree<Int> {}
        val copy = tree.mutableCopy()

        assertNull(copy.root)
        assertEquals(tree, copy)
    }

    @Test
    fun `mutableCopy of tree is deep`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(4)
                }
                node(3)
            }
        }

        val copy = tree.mutableCopy().apply { validate() }

        assertEquals(tree, copy)
        val original = tree.root!!
        val copied = copy.root!!
        assertNotSame(original, copied)
        assertNotSame(original.children[0], copied.children[0])
        assertNotSame(original.children[0].children[0], copied.children[0].children[0])
        assertNull(copied.parent)
        assertSame(copied, copied.children[1].parent)
    }

    @Test
    fun `modifications of mutable copy do not affect the original`() {
        val tree = tree {
            node(1) {
                node(2)
            }
        }

        val copy = tree.mutableCopy()
        copy.root!!.data = 10
        copy.root!!.children.add(mutableNode(3))
        copy.validate()

        val expected = tree {
            node(1) {
                node(2)
            }
        }
        assertEquals(expected, tree)
    }

    @Test
    fun `mutableCopy of node detaches it from its parent`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                }
            }
        }

        val subtree = tree.root!!.children.single()
        val copy = subtree.mutableCopy()
        copy.validate()

        assertEquals(subtree, copy)
        assertNull(copy.parent)
        assertSame(copy, copy.children.single().parent)
    }

    @Test
    fun `mutableCopy of node can be attached to another tree`() {
        val tree = mutableTree {
            node(1) {
                node(2) {
                    node(3)
                }
            }
        }

        val root = tree.root!!
        val copy = root.children.single().mutableCopy()
        root.children.add(copy)
        tree.validate()

        val expected = tree {
            node(1) {
                node(2) {
                    node(3)
                }
                node(2) {
                    node(3)
                }
            }
        }
        assertEquals(expected, tree)
    }

    @Test
    fun `forEach on tree with null root`() {
        val tree = tree<Int> {}

        val visited = mutableListOf<Int>()
        tree.forEach { visited.add(it) }

        assertEquals(listOf<Int>(), visited)
    }

    @Test
    fun `forEach on single node tree`() {
        val tree = tree {
            node(42)
        }

        val visited = mutableListOf<Int>()
        tree.forEach { visited.add(it) }

        assertEquals(listOf(42), visited)
    }

    @Test
    fun `forEach on data visits all nodes in depth-first order`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(3) {
                    node(6)
                }
            }
        }

        val visited = mutableListOf<Int>()
        tree.forEach { visited.add(it) }

        assertEquals(listOf(1, 2, 4, 5, 3, 6), visited)
    }

    @Test
    fun `forEachNode with null root`() {
        val tree = tree<String> {}

        val nodes = mutableListOf<Tree.Node<String>>()
        tree.forEachNode { node -> nodes.add(node) }

        assertEquals(listOf<Tree.Node<String?>>(), nodes)
    }

    @Test
    fun `forEachNode visits all nodes in depth-first order`() {
        var a: Tree.Node<String>? = null
        var b: Tree.Node<String>? = null
        var c: Tree.Node<String>? = null
        var d: Tree.Node<String>? = null
        val tree = mutableTree {
            a = node("a") {
                b = node("b") {
                    c = node("c")
                }
                d = node("d")
            }
        }

        val visited = mutableListOf<Tree.Node<String>>()
        tree.forEachNode { node -> visited.add(node) }

        assertEquals(listOf(a, b, c, d), visited)
    }

    @Test
    fun `forEach on nodes provides correct parent-child relationships`() {
        var a: Tree.Node<String>? = null
        var b: Tree.Node<String>? = null
        var c: Tree.Node<String>? = null
        val tree = mutableTree {
            a = node("a") {
                b = node("b")
                c = node("c")
            }
        }

        val childParentPairs = mutableListOf<Pair<Tree.Node<String>, Tree.Node<String>?>>()
        tree.forEachNode { node ->
            childParentPairs.add(node to node.parent)
        }

        assertEquals(
            listOf(
                a to null,
                b to a,
                c to a,
            ),
            childParentPairs
        )
    }

    @Test
    fun `map on tree with null root`() {
        val tree = tree<Int> {}

        val mappedTree = tree.map { it * 10 }
            .apply { validate() }

        val expected = tree<Int?> {}
        assertEquals(expected, mappedTree)
    }

    @Test
    fun `map on single node tree`() {
        val tree = tree {
            node(1)
        }
        val mappedTree = tree.map { it * 2 }
            .apply { validate() }

        val expected = tree {
            node(2)
        }
        assertEquals(expected, mappedTree)
    }

    @Test
    fun `map on tree with children`() {
        val tree = tree {
            node(1) {
                node(2)
                node(3)
            }
        }
        val mappedTree = tree.map { it * 10 }
            .apply { validate() }

        val expected = tree {
            node(10) {
                node(20)
                node(30)
            }
        }
        assertEquals(expected, mappedTree)
    }

    @Test
    fun `map on nested tree structure`() {
        val tree = tree {
            node("a") {
                node("b") {
                    node("d")
                    node("e")
                }
                node("c")
            }
        }
        val mappedTree = tree.map { it.uppercase() }
            .apply { validate() }

        val expected = tree {
            node("A") {
                node("B") {
                    node("D")
                    node("E")
                }
                node("C")
            }
        }
        assertEquals(expected, mappedTree)
    }

    @Test
    fun `map with type transformation`() {
        val tree = tree {
            node("hello") {
                node("world") {
                    node("!")
                }
                node("test")
            }
        }
        val mappedTree = tree.map { it.length }
            .apply { validate() }

        val expected = tree {
            node(5) {
                node(5) {
                    node(1)
                }
                node(4)
            }
        }
        assertEquals(expected, mappedTree)
    }

    @Test
    fun `filter on empty tree returns empty tree`() {
        val tree = tree<Int> {}

        val filteredTree = tree.filter { it > 5 }
            .apply { validate() }

        val expected = tree<Int> {}
        assertEquals(expected, filteredTree)
    }

    @Test
    fun `filter removes some elements but not all`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(3) {
                    node(6)
                }
            }
        }

        val filteredTree = tree.filter { it % 2 == 0 }
            .apply { validate() }

        val expected = tree {
            node(1) {
                node(2) {
                    node(4)
                }
                node(3) {
                    node(6)
                }
            }
        }
        assertEquals(expected, filteredTree)
    }

    @Test
    fun `filter removes all elements returns empty tree`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                }
                node(4)
            }
        }

        val filteredTree = tree.filter { it > 10 }
            .apply { validate() }

        val expected = tree<Int> {}
        assertEquals(expected, filteredTree)
    }

    @Test
    fun `filter does not remove any element returns same tree`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                    node(4)
                }
                node(5) {
                    node(6)
                }
            }
        }

        val filteredTree = tree.filter { it > 0 }
            .apply { validate() }

        val expected = tree {
            node(1) {
                node(2) {
                    node(3)
                    node(4)
                }
                node(5) {
                    node(6)
                }
            }
        }
        assertEquals(expected, filteredTree)
    }

    @Test
    fun `filter removes only leafs keeps parent nodes`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(3) {
                    node(6)
                    node(7)
                }
            }
        }

        val filteredTree = tree.filter { it <= 3 }
            .apply { validate() }

        val expected = tree {
            node(1) {
                node(2)
                node(3)
            }
        }
        assertEquals(expected, filteredTree)
    }

    @Test
    fun `filter removes intermediate nodes but keeps leafs`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(5)
                    node(6)
                }
                node(3) {
                    node(7)
                }
                node(4) {
                    node(8)
                }
            }
        }

        val filteredTree = tree.filter { it == 1 || it >= 5 }
            .apply { validate() }

        val expected = tree {
            node(1) {
                node(2) {
                    node(5)
                    node(6)
                }
                node(3) {
                    node(7)
                }
                node(4) {
                    node(8)
                }
            }
        }
        assertEquals(expected, filteredTree)
    }

    @Test
    fun `transform on tree with null root`() {
        val tree = tree<Int?> {}

        val transformedTree = tree.transform { it.lazyCopy() }
            .apply { validate() }

        val expected = tree<Int?> {}
        assertEquals(expected, transformedTree)
    }

    @Test
    fun `structure-preserving transform is equivalent to map`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                    node(4)
                }
                node(5) {
                    node(6)
                }
            }
        }

        val transformedTree = tree.transform {
            node(10 * it.data) { it.children.forEach { child -> lazyNode(child) } }
        }.apply { validate() }

        val mappedTree = tree.map { 10 * it  }
            .apply { validate() }

        val expected = tree {
            node(10) {
                node(20) {
                    node(30)
                    node(40)
                }
                node(50) {
                    node(60)
                }
            }
        }

        assertEquals(expected, transformedTree)
        assertEquals(mappedTree, transformedTree)
    }

    @Test
    fun `transform inserts nodes`() {
        val tree = tree {
            node(1) {
                node(2)
                node(3) {
                    node(4)
                }
            }
        }

        val transformedTree = tree.transform {
            if (it.children.isEmpty()) it else
                node(it.data) {
                    node(it.data *  10)
                    it.children.forEach { child -> lazyNode(child) }
                    node(it.data * -10)
                }
        }.apply { validate() }

        val expected = tree {
            node(1) {
                node(10)
                node(2)
                node(3) {
                    node(30)
                    node(4)
                    node(-30)
                }
                node(-10)
            }
        }
        assertEquals(expected, transformedTree)
    }

    @Test
    fun `transform collapses single child nodes`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(6) {
                    node(7)
                }
            }
        }

        val transformedTree = tree.transform { node ->
            if (node.children.size == 1) node.children.first() else node
        }.apply { validate() }

        val expected = tree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(7)
            }
        }
        assertEquals(expected, transformedTree)
    }

    @Test
    fun `transform drops N children`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                }
                node(4)
                node(5) {
                    node(6)
                    node(7)
                    node(8)
                }
            }
        }

        val transformedTree = tree.transform {
            node(it.data) {
                it.children.drop(2).forEach { child -> lazyNode(child) }
            }
        }.apply { validate() }

        val expected = tree {
            node(1) {
                node(5) {
                    node(8)
                }
            }
        }
        assertEquals(expected, transformedTree)
    }

    @Test
    fun `transform with complex structural changes deep in tree`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(3) {
                    node(6) {
                        node(7)
                    }
                    node(8)
                }
            }
        }

        val transformedTree = tree.transform {
            node(it.data) {
                when {
                    // Flatten: if a node has exactly 1 child, skip this level and promote grandchildren
                    it.children.size == 1 -> {
                        val child = it.children.first()
                        child.children.forEach { grandchild ->
                            node(grandchild.data * 10) {
                                // Keep great-grandchildren as is
                                grandchild.children.forEach { greatGrandchildren ->
                                    lazyNode(greatGrandchildren)
                                }
                            }
                        }
                    }
                    // Otherwise, add an extra wrapper node for each child
                    else -> {
                        it.children.forEach { child ->
                            // Add wrapper
                            node(child.data * 10) {
                                lazyNode(child)
                            }
                        }
                    }
                }
            }
        }.apply { validate() }

        val expected = tree {
            node(1) {
                node(20) {
                    node(40)
                    node(50)
                }
                node(30) {
                    node(60)
                    node(80)
                }
            }
        }
        assertEquals(expected, transformedTree)
    }

    @Test
    fun `find on empty tree returns null`() {
        val tree = tree<Int> {}

        val result = tree.find { it == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `find on single-node tree with match returns data`() {
        val tree = tree {
            node(42)
        }

        val result = tree.find { it == 42 }

        assertEquals(42, result)
    }

    @Test
    fun `find on single-node tree with no match returns null`() {
        val tree = tree {
            node(1)
        }

        val result = tree.find { it == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `find on larger tree with multiple occurrences returns match`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(5)
                    node(6)
                }
                node(3) {
                    node(5)
                    node(7)
                }
                node(4) {
                    node(5)
                }
            }
        }

        val result = tree.find { it == 5 }

        assertEquals(5, result)
    }

    @Test
    fun `find on larger tree with no occurrences returns null`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                    node(4)
                }
                node(5) {
                    node(6)
                    node(7)
                }
            }
        }

        val result = tree.find { it == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `findNode on empty tree returns null`() {
        val tree = tree<Int> {}

        val result = tree.findNode { it.data == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `findNode on single-node tree with match returns node`() {
        var target: Tree.Node<Int>? = null
        val tree = mutableTree {
            target = node(42)
        }

        val result = tree.findNode { it.data == 42 }

        assertEquals(target, result)
    }

    @Test
    fun `findNode on single-node tree with no match returns null`() {
        val tree = tree {
            node(1)
        }

        val result = tree.findNode { it.data == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `findNode on larger tree with multiple occurrences returns first match`() {
        var first: Tree.Node<Int>? = null
        var second: Tree.Node<Int>? = null
        var third: Tree.Node<Int>? = null
        val tree = mutableTree {
            node(1) {
                node(2) {
                    first = node(5)
                    node(6)
                }
                node(3) {
                    second = node(5)
                    node(7)
                }
                node(4) {
                    third = node(5)
                }
            }
        }

        val result = tree.findNode { it.data == 5 }

        assertEquals(first, result)
    }

    @Test
    fun `findNode on larger tree with no occurrences returns null`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                    node(4)
                }
                node(5) {
                    node(6)
                    node(7)
                }
            }
        }

        val result = tree.findNode { it.data == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `findNode when root matches and deeper node also matches returns root`() {
        var root: Tree.Node<Int>? = null
        var deeper: Tree.Node<Int>? = null
        val tree = mutableTree {
            root = node(5) {
                node(2) {
                    deeper = node(5)
                    node(6)
                }
                node(3)
            }
        }

        val result = tree.findNode { it.data == 5 }

        assertEquals(root, result)
    }

    @Test
    fun `findNode when root matches and no deeper node matches returns root`() {
        var root: Tree.Node<Int>? = null
        val tree = mutableTree {
            root = node(5) {
                node(2) {
                    node(3)
                    node(6)
                }
                node(7)
            }
        }

        val result = tree.findNode { it.data == 5 }

        assertEquals(root, result)
    }

    @Test
    fun `findLast on empty tree returns null`() {
        val tree = tree<Int> {}

        val result = tree.findLast { it == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `findLast on single-node tree with match returns data`() {
        val tree = tree {
            node(42)
        }

        val result = tree.findLast { it == 42 }

        assertEquals(42, result)
    }

    @Test
    fun `findLast on single-node tree with no match returns null`() {
        val tree = tree {
            node(1)
        }

        val result = tree.findLast { it == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `findLast on larger tree with multiple occurrences returns match`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(5)
                    node(6)
                }
                node(3) {
                    node(5)
                    node(7)
                }
                node(4) {
                    node(5)
                }
            }
        }

        val result = tree.findLast { it == 5 }

        assertEquals(5, result)
    }

    @Test
    fun `findLast on larger tree with no occurrences returns null`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                    node(4)
                }
                node(5) {
                    node(6)
                    node(7)
                }
            }
        }

        val result = tree.findLast { it == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `findLastNode on empty tree returns null`() {
        val tree = tree<Int> {}

        val result = tree.findLastNode { it.data == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `findLastNode on single-node tree with match returns node`() {
        var target: Tree.Node<Int>? = null
        val tree = mutableTree {
            target = node(42)
        }

        val result = tree.findLastNode { it.data == 42 }

        assertEquals(target, result)
    }

    @Test
    fun `findLastNode on single-node tree with no match returns null`() {
        val tree = tree {
            node(1)
        }

        val result = tree.findLastNode { it.data == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `findLastNode on larger tree with multiple occurrences returns last match`() {
        var first: Tree.Node<Int>? = null
        var second: Tree.Node<Int>? = null
        var third: Tree.Node<Int>? = null
        val tree = mutableTree {
            node(1) {
                node(2) {
                    first = node(5)
                    node(6)
                }
                node(3) {
                    second = node(5)
                    node(7)
                }
                node(4) {
                    third = node(5)
                }
            }
        }

        val result = tree.findLastNode { it.data == 5 }

        assertEquals(third, result)
    }

    @Test
    fun `findLastNode on larger tree with no occurrences returns null`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(3)
                    node(4)
                }
                node(5) {
                    node(6)
                    node(7)
                }
            }
        }

        val result = tree.findLastNode { it.data == 42 }

        assertEquals(null, result)
    }

    @Test
    fun `findLastNode when root matches and deeper node also matches returns deeper node`() {
        var root: Tree.Node<Int>? = null
        var deeper: Tree.Node<Int>? = null
        val tree = mutableTree {
            root = node(5) {
                node(2) {
                    deeper = node(5)
                    node(6)
                }
                node(3)
            }
        }

        val result = tree.findLastNode { it.data == 5 }

        assertEquals(deeper, result)
    }

    @Test
    fun `findLastNode when root matches and no deeper node matches returns root`() {
        var root: Tree.Node<Int>? = null
        val tree = mutableTree {
            root = node(5) {
                node(2) {
                    node(3)
                    node(6)
                }
                node(7)
            }
        }

        val result = tree.findLastNode { it.data == 5 }

        assertEquals(root, result)
    }

    @Test
    fun `firstLeaf and lastLeaf on a singleton node return null`() {
        val root = node(1)

        assertNull(root.firstLeaf())
        assertNull(root.lastLeaf())
    }

    @Test
    fun `firstLeaf and lastLeaf follow the outermost children`() {
        var first: Tree.Node<Int>? = null
        var last: Tree.Node<Int>? = null
        val tree = mutableTree {
            node(1) {
                node(2) {
                    first = node(3)
                    node(4)
                }
                node(5) {
                    node(6)
                    last = node(7)
                }
            }
        }
        val root = tree.root!!

        assertSame(first, root.firstLeaf())
        assertSame(last, root.lastLeaf())
    }

    /**
     * Squashes consecutive sibling nodes for which [relation] holds (on their data)
     * into a single node, keeping the data of the first node in the run and
     * concatenating the children of all merged siblings under it.
     */
    private fun Tree<Int>.squashRelated(relation: (Int, Int) -> Boolean): Tree<Int> = squash(
        relation = { a, b -> relation(a.data, b.data) },
        merge = { nodes -> nodes.first().data }
    )

    @Test
    fun `squash on empty tree returns empty tree`() {
        val tree = tree<Int> {}

        val squashedTree = tree.squashRelated { a, b -> a == b }
            .apply { validate() }

        val expected = tree<Int> {}
        assertEquals(expected, squashedTree)
    }

    @Test
    fun `squash on single node tree returns same tree`() {
        val tree = tree {
            node(42)
        }

        val squashedTree = tree.squashRelated { a, b -> a == b }
            .apply { validate() }

        val expected = tree {
            node(42)
        }
        assertEquals(expected, squashedTree)
    }

    @Test
    fun `squash with relation never holding preserves tree`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(3) {
                    node(6)
                }
            }
        }

        val squashedTree = tree.squashRelated { _, _ -> false }
            .apply { validate() }

        val expected = tree {
            node(1) {
                node(2) {
                    node(4)
                    node(5)
                }
                node(3) {
                    node(6)
                }
            }
        }
        assertEquals(expected, squashedTree)
    }

    @Test
    fun `squash merges consecutive equal siblings concatenating their children`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(10)
                }
                node(2) {
                    node(11)
                }
                node(3) {
                    node(12)
                }
            }
        }

        val squashedTree = tree.squashRelated { a, b -> a == b }
            .apply { validate() }

        val expected = tree {
            node(1) {
                node(2) {
                    node(10)
                    node(11)
                }
                node(3) {
                    node(12)
                }
            }
        }
        assertEquals(expected, squashedTree)
    }

    @Test
    fun `squash merges only consecutive runs of equal siblings`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(10)
                }
                node(2) {
                    node(11)
                }
                node(3) {
                    node(12)
                }
                node(2) {
                    node(13)
                }
            }
        }

        val squashedTree = tree.squashRelated { a, b -> a == b }
            .apply { validate() }

        val expected = tree {
            node(1) {
                node(2) {
                    node(10)
                    node(11)
                }
                node(3) {
                    node(12)
                }
                node(2) {
                    node(13)
                }
            }
        }
        assertEquals(expected, squashedTree)
    }

    @Test
    fun `squash with relation always holding merges all siblings`() {
        val tree = tree {
            node(1) {
                node(2)
                node(3)
                node(4)
            }
        }

        val squashedTree = tree.squashRelated { _, _ -> true }
            .apply { validate() }

        val expected = tree {
            node(1) {
                node(2)
            }
        }
        assertEquals(expected, squashedTree)
    }

    @Test
    fun `squash merges siblings at multiple levels`() {
        val tree = tree {
            node(1) {
                node(2) {
                    node(5) {
                        node(50)
                    }
                    node(5) {
                        node(51)
                    }
                }
                node(2) {
                    node(6) {
                        node(60)
                    }
                }
            }
        }

        val squashedTree = tree.squashRelated { a, b -> a == b }
            .apply { validate() }

        val expected = tree {
            node(1) {
                node(2) {
                    node(5) {
                        node(50)
                        node(51)
                    }
                    node(6) {
                        node(60)
                    }
                }
            }
        }
        assertEquals(expected, squashedTree)
    }
}
