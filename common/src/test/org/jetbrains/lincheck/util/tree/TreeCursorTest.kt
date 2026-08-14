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
import org.junit.Assert.*

class TreeCursorTest {

    @Test
    fun `search on empty tree returns null cursor`() {
        val emptyTree = tree<Int> { }

        val cursor = TreeCursor.atRoot(emptyTree)

        assertNull(cursor)
    }

    @Test
    fun `search on single-node tree returns cursor at root`() {
        val testTree = tree {
            node(1)
        }

        val cursor = TreeCursor.atRoot(testTree)

        assertNotNull(cursor)
        assertSame(testTree.root, cursor!!.currentNode)
        assertEquals(listOf(1), cursor.walkForward().map { it.data }.toList())
    }

    @Test
    fun `search on single-node tree where root matches predicate returns root`() {
        val testTree = tree {
            node(1)
        }

        val cursor = TreeCursor.atRoot(testTree)!!
        val found = cursor.findNextNode { it.data == 1 }

        assertSame(testTree.root, found)
    }

    @Test
    fun `search on single-node tree returns null when root does not match predicate`() {
        val testTree = tree {
            node(1)
        }

        val cursor = TreeCursor.atRoot(testTree)!!
        val found = cursor.findNextNode { it.data > 100 }

        assertNull(found)
    }

    @Test
    fun `search in tree where root matches predicate returns root`() {
        val testTree = tree {
            node(42) {
                node(1)
                node(2)
            }
        }

        val cursor = TreeCursor.atRoot(testTree)!!
        val found = cursor.findNextNode { it.data == 42 }

        assertNotNull(found)
        assertSame(testTree.root, found)
    }

    @Test
    fun `continuing search finds child of last found node`() {
        var match1: Tree.Node<String>? = null
        var match2: Tree.Node<String>? = null
        val testTree = mutableTree {
            node("root") {
                match1 = node("match1") {
                    match2 = node("match2")
                }
            }
        }

        val cursor = TreeCursor.atRoot(testTree)!!
        val predicate: (Tree.Node<String>) -> Boolean = { it.data.startsWith("match") }

        val first = cursor.findNextNode(predicate)
        assertSame(match1, first)

        val second = cursor.findNextNode(predicate)
        assertSame(match2, second)

        // Verify it's actually a child of the first match
        assertSame(first, second!!.parent)
    }

    @Test
    fun `continuing search finds sibling of last found node which has children`() {
        var match1: Tree.Node<String>? = null
        var match2: Tree.Node<String>? = null
        val testTree = mutableTree {
            node("root") {
                match1 = node("match1") {
                    node("child1")
                    node("child2")
                }
                match2 = node("match2")
            }
        }

        val cursor = TreeCursor.atRoot(testTree)!!
        val predicate: (Tree.Node<String>) -> Boolean = { it.data.startsWith("match") }

        val first = cursor.findNextNode(predicate)
        assertSame(match1, first)

        assertNotNull(first)
        assertSame(match1, first)

        val second = cursor.findNextNode(predicate)
        assertSame(match2, second)

        // Verify they are siblings (same parent)
        assertSame(first?.parent, second?.parent)
    }

    @Test
    fun `continuing search finds occurrence in another subtree`() {
        var match1: Tree.Node<String>? = null
        var match2: Tree.Node<String>? = null
        val testTree = mutableTree {
            node("root") {
                node("subtree1") {
                    match1 = node("match1") {
                        node("deep1")
                    }
                }
                node("subtree2") {
                    node("nested") {
                        match2 = node("match2")
                    }
                }
            }
        }

        val cursor = TreeCursor.atRoot(testTree)!!
        val predicate: (Tree.Node<String>) -> Boolean = { it.data.startsWith("match") }

        val first = cursor.findNextNode(predicate)
        assertSame(match1, first)

        val second = cursor.findNextNode(predicate)
        assertSame(match2, second)

        // Verify they are in different subtrees
        assertNotSame(first?.parent, second?.parent)
    }

    @Test
    fun `search returns null when no occurrences in tree`() {
        val testTree = tree {
            node(1) {
                node(2)
                node(3) {
                    node(4)
                }
            }
        }

        val cursor = TreeCursor.atRoot(testTree)!!
        val found = cursor.findNextNode { it.data > 100 }

        assertNull(found)
    }

    @Test
    fun `search from last occurrence returns null`() {
        var match1: Tree.Node<Int>? = null
        var match2: Tree.Node<Int>? = null
        val testTree = mutableTree {
            node(1) {
                node(2) {
                    match1 = node(10)
                }
                node(3) {
                    match2 = node(20)
                }
            }
        }

        val cursor = TreeCursor.atRoot(testTree)!!
        val predicate: (Tree.Node<Int>) -> Boolean = { it.data >= 10 }

        val first = cursor.findNextNode(predicate)
        assertSame(match1, first)

        val second = cursor.findNextNode(predicate)
        assertSame(match2, second)

        val third = cursor.findNextNode(predicate)
        assertNull(third)
    }

    @Test
    fun `repeated searches after end of tree return null`() {
        var match: Tree.Node<Int>? = null
        val testTree = mutableTree {
            node(1) {
                match = node(2) {
                    node(3)
                }
                node(4) {
                    node(5)
                    node(6)
                }
                node(7)
            }
        }

        val cursor = TreeCursor.atRoot(testTree)!!

        // Find the only match
        val found = cursor.findNextNode { it.data == 2 }
        assertSame(match, found)

        // Continue searching - should reach the end
        val afterEnd1 = cursor.findNextNode { it.data == 2 }
        assertNull(afterEnd1)

        // Repeated searches should still return null
        val afterEnd2 = cursor.findNextNode { it.data == 2 }
        assertNull(afterEnd2)

        val afterEnd3 = cursor.findNextNode { it.data == 2 }
        assertNull(afterEnd3)
    }

    @Test
    fun `search on node returns cursor starting from that node`() {
        var b: Tree.Node<String>? = null
        var match: Tree.Node<String>? = null
        val testTree = mutableTree {
            node("root") {
                node("a") {
                    node("match1")
                }
                b = node("b") {
                    match = node("match2")
                }
            }
        }

        val cursor = TreeCursor.at(b!!)

        val found = cursor.findNextNode { it.data.startsWith("match") }
        assertSame(match, found)

        // Should not find match1 since we started from node "b"
        val next = cursor.findNextNode { it.data.startsWith("match") }
        assertNull(next)
    }

    @Test
    fun `backward search on empty tree returns null cursor`() {
        val emptyTree = tree<Int> { }

        val cursor = TreeCursor.atLastLeaf(emptyTree)

        assertNull(cursor)
    }

    @Test
    fun `backward search on single-node tree returns cursor at root`() {
        val testTree = tree {
            node(1)
        }

        val cursor = TreeCursor.atLastLeaf(testTree)

        assertNotNull(cursor)
        assertSame(testTree.root, cursor!!.currentNode)
        assertEquals(listOf(1), cursor.walkBackward().map { it.data }.toList())
    }

    @Test
    fun `backward search on single-node tree where root matches predicate returns root`() {
        val testTree = tree {
            node(1)
        }

        val cursor = TreeCursor.atLastLeaf(testTree)!!
        val found = cursor.findPreviousNode { it.data == 1 }

        assertSame(testTree.root, found)
    }

    @Test
    fun `backward search on single-node tree returns null when root does not match predicate`() {
        val testTree = tree {
            node(1)
        }

        val cursor = TreeCursor.atLastLeaf(testTree)!!
        val found = cursor.findPreviousNode { it.data > 100 }

        assertNull(found)
    }

    @Test
    fun `backward search in tree where root matches predicate returns root`() {
        val testTree = tree {
            node(42) {
                node(1)
                node(2)
            }
        }

        val cursor = TreeCursor.atLastLeaf(testTree)!!
        val found = cursor.findPreviousNode { it.data == 42 }

        assertNotNull(found)
        assertSame(testTree.root, found)
    }

    @Test
    fun `continuing backward search finds parent of last found node`() {
        var match1: Tree.Node<String>? = null
        var match2: Tree.Node<String>? = null
        val testTree = mutableTree {
            node("root") {
                match2 = node("match1") {
                    match1 = node("match2")
                }
            }
        }

        val cursor = TreeCursor.atLastLeaf(testTree)!!
        val predicate: (Tree.Node<String>) -> Boolean = { it.data.startsWith("match") }

        val first = cursor.findPreviousNode(predicate)
        assertSame(match1, first)

        val second = cursor.findPreviousNode(predicate)
        assertSame(match2, second)

        // Verify it's actually a parent of the first match
        assertSame(second, first!!.parent)
    }

    @Test
    fun `continuing backward search finds sibling of last found node which has children`() {
        var match1: Tree.Node<String>? = null
        var match2: Tree.Node<String>? = null
        val testTree = mutableTree {
            node("root") {
                match2 = node("match1") {
                    node("child1")
                    node("child2")
                }
                match1 = node("match2")
            }
        }

        val cursor = TreeCursor.atLastLeaf(testTree)!!
        val predicate: (Tree.Node<String>) -> Boolean = { it.data.startsWith("match") }

        val first = cursor.findPreviousNode(predicate)
        assertSame(match1, first)

        assertNotNull(first)
        assertSame(match1, first)

        val second = cursor.findPreviousNode(predicate)
        assertSame(match2, second)

        // Verify they are siblings (same parent)
        assertSame(first?.parent, second?.parent)
    }

    @Test
    fun `continuing backward search finds occurrence in another subtree`() {
        var match1: Tree.Node<String>? = null
        var match2: Tree.Node<String>? = null
        val testTree = mutableTree {
            node("root") {
                node("subtree1") {
                    match2 = node("match1") {
                        node("deep1")
                    }
                }
                node("subtree2") {
                    node("nested") {
                        match1 = node("match2")
                    }
                }
            }
        }

        val cursor = TreeCursor.atLastLeaf(testTree)!!
        val predicate: (Tree.Node<String>) -> Boolean = { it.data.startsWith("match") }

        val first = cursor.findPreviousNode(predicate)
        assertSame(match1, first)

        val second = cursor.findPreviousNode(predicate)
        assertSame(match2, second)

        // Verify they are in different subtrees
        assertNotSame(first?.parent, second?.parent)
    }

    @Test
    fun `backward search returns null when no occurrences in tree`() {
        val testTree = tree {
            node(1) {
                node(2)
                node(3) {
                    node(4)
                }
            }
        }

        val cursor = TreeCursor.atLastLeaf(testTree)!!
        val found = cursor.findPreviousNode { it.data > 100 }

        assertNull(found)
    }

    @Test
    fun `backward search from last occurrence returns null`() {
        var match1: Tree.Node<Int>? = null
        var match2: Tree.Node<Int>? = null
        val testTree = mutableTree {
            node(1) {
                node(2) {
                    match2 = node(10)
                }
                node(3) {
                    match1 = node(20)
                }
            }
        }

        val cursor = TreeCursor.atLastLeaf(testTree)!!
        val predicate: (Tree.Node<Int>) -> Boolean = { it.data >= 10 }

        val first = cursor.findPreviousNode(predicate)
        assertSame(match1, first)

        val second = cursor.findPreviousNode(predicate)
        assertSame(match2, second)

        val third = cursor.findPreviousNode(predicate)
        assertNull(third)
    }

    @Test
    fun `repeated backward searches after end of tree return null`() {
        var match: Tree.Node<Int>? = null
        val testTree = mutableTree {
            node(1) {
                match = node(2) {
                    node(3)
                }
                node(4) {
                    node(5)
                    node(6)
                }
                node(7)
            }
        }

        val cursor = TreeCursor.atLastLeaf(testTree)!!

        // Find the only match
        val found = cursor.findPreviousNode { it.data == 2 }
        assertSame(match, found)

        // Continue searching - should reach the end
        val afterEnd1 = cursor.findPreviousNode { it.data == 2 }
        assertNull(afterEnd1)

        // Repeated searches should still return null
        val afterEnd2 = cursor.findPreviousNode { it.data == 2 }
        assertNull(afterEnd2)

        val afterEnd3 = cursor.findPreviousNode { it.data == 2 }
        assertNull(afterEnd3)
    }

    @Test
    fun `backward search on node returns cursor starting from that node`() {
        var b: Tree.Node<String>? = null
        var match: Tree.Node<String>? = null
        val testTree = mutableTree {
            node("root") {
                b = node("a") {
                    match = node("match1")
                }
                node("b") {
                    node("match2")
                }
            }
        }

        val cursor = TreeCursor.at(b!!)

        val found = cursor.findPreviousNode { it.data.startsWith("match") }
        assertSame(match, found)

        // Should not find match2 since we started from node "a" and went backward
        val next = cursor.findPreviousNode { it.data.startsWith("match") }
        assertNull(next)
    }
}