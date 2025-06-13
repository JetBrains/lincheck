/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

class UninitializedConstructorTest {
    companion object {
        val log = StringBuilder()
    }

    class Node(val value: Int, val left: Node? = null, val right: Node? = null, var parent: Node? = null) {
        init {
            left?.parent = this
            right?.parent = this
            log.appendLine("$this $left $right")
        }
        
        override fun toString(): String {
            return "Node($value, $left, $right)"
        }

        override fun equals(other: Any?): Boolean {
            return other is Node && other.value == value && other.left == left && other.right == right
        }
        
        override fun hashCode(): Int {
            return value.hashCode() * 31 * 31 + (left?.hashCode() ?: 0) * 31 + (right?.hashCode() ?: 0)
        }
    }

    @Operation
    fun createParentNode(): Pair<Node, String> {
        log.clear()
        val l = Node(1)
        val r = Node(2)
        val parent = Node(3, l, r)
        return parent to log.toString()
    }

    @Test
    fun testModelChecking() = ModelCheckingOptions().check(this::class)
}
