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

class TreeTest {

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
        val tree = tree {
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
        val tree = tree {
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
    fun `transform on tree with null root`() {
        val tree = tree<Int?> {}

        val transformedTree = tree.transform { it.copy() }
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
            it.copy(newData = it.data * 10)
        }.apply { validate() }

        val mappedTree = tree.map { it * 10 }
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
                    it.children.forEach { child -> node(child) }
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
                it.children.drop(2).forEach { child -> node(child) }
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
                                    node(greatGrandchildren)
                                }
                            }
                        }
                    }
                    // Otherwise, add an extra wrapper node for each child
                    else -> {
                        it.children.forEach { child ->
                            // Add wrapper
                            node(child.data * 10) {
                                node(child)
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
}
