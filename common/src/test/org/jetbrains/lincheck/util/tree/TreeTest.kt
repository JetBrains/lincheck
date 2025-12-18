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
}
