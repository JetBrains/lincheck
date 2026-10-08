/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions

import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.ExpressionNode
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.ExpressionStatus
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.ExpressionTarget
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.InheritedExpressionTarget
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.junit.Test

/** Agent-side counterpart of the IDE Java watch compilation matrix. */
class JavaWatchCompilationTest : AbstractExpressionCompilationTest() {
    private val java = SnapshotBreakpoint.EXPRESSION_LANGUAGE_JAVA

    @Test
    fun `single and few watches`() {
        val locals = listOf(reference("name", String::class.java, "Davis"), int("page", 1))
        assertWatches(java, listOf("name"), listOf("Davis"), locals)
        assertWatches(java, listOf("name", "page", "page + 1"), listOf("Davis", 1, 2), locals)
    }

    @Test
    fun `primitive watches`() {
        val locals = listOf(
            int("i", 42), long("l", 100L), double("d", 1.5), float("f", 2.5f),
            char("c", 'x'), boolean("b", true),
        )
        assertWatches(java, listOf("i", "l", "d", "f", "c", "b"), listOf(42, 100L, 1.5, 2.5f, 'x', true), locals)
    }

    @Test
    fun `string method watches`() {
        val locals = listOf(reference("s", String::class.java, "hello"))
        assertWatches(
            java,
            listOf("s.length()", "s.charAt(0)", "s.contains(\"ell\")", "s.isEmpty()"),
            listOf(5, 'h', true, false),
            locals,
        )
    }

    @Test
    fun `arithmetic comparison and ternary watches`() {
        val locals = listOf(int("x", 7), int("y", 3))
        assertWatches(
            java,
            listOf("x + y", "x - y", "x * y", "x % y", "x > y", "x > 0 ? \"pos\" : \"neg\""),
            listOf(10, 4, 21, 1, true, "pos"),
            locals,
        )
    }

    @Test
    fun `type check cast null and concatenation watches`() {
        val locals = listOf(reference("obj", Any::class.java, "hello"), int("page", 1))
        assertWatches(
            java,
            listOf("obj instanceof String", "((String) obj).length()", "null", "obj + \"-\" + page"),
            listOf(true, 5, null, "hello-1"),
            locals,
        )
    }

    @Test
    fun `field method and static watches`() {
        val receiver = ExpressionTarget("secret", 10, true)
        assertWatches(
            java,
            listOf("secret", "count", "doubled()", "MODE", "LIMIT", "isProduction()"),
            listOf("secret", 10, 20, "prod", 3, true),
            receiver = receiver,
        )
    }

    @Test
    fun `nested application object watches`() {
        val receiver = ExpressionTarget("secret", 10, true)
        assertWatches(
            java,
            listOf("node.text", "node.value", "node.next().text", "node.next().value"),
            listOf("root", 5, "leaf", 9),
            receiver = receiver,
        )
    }

    @Test
    fun `inherited private field watches through a subtype local`() {
        val owner = InheritedExpressionTarget()
        assertWatches(
            java,
            listOf("owner.inheritedSecret", "owner"),
            listOf("base-private", owner),
            locals = listOf(reference("owner", InheritedExpressionTarget::class.java, owner)),
            enclosingType = ExpressionNode::class.java,
        )
    }

    @Test
    fun `inherited private methods and hidden public fields keep their declaring class`() {
        val owner = InheritedExpressionTarget()
        assertWatches(
            java,
            listOf("owner.inheritedSecret()", "owner.count"),
            listOf("base-private", 10),
            locals = listOf(reference("owner", InheritedExpressionTarget::class.java, owner)),
            enclosingType = ExpressionNode::class.java,
        )
    }

    @Test
    fun `enum watches`() {
        val locals = listOf(reference("status", ExpressionStatus::class.java, ExpressionStatus.ACTIVE))
        assertWatches(java, listOf("status", "status == ExpressionStatus.ACTIVE"), listOf(ExpressionStatus.ACTIVE, true), locals)
    }

    @Test
    fun `many watches`() {
        val locals = listOf(reference("name", String::class.java, "Davis"), int("page", 1))
        assertWatches(
            java,
            listOf(
                "name", "page", "page + 1", "page * 2", "page - 1", "name.length()",
                "page > 0", "page < 0", "name.equals(\"Davis\")", "null",
            ),
            listOf("Davis", 1, 2, 2, 0, 5, true, false, true, null),
            locals,
        )
    }
}
