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

import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.ExpressionStatus
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.ExpressionTarget
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.KotlinExpressionNode
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.KotlinExpressionTarget
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.junit.Ignore
import org.junit.Test

/** Agent-side counterpart of the IDE Kotlin watch compilation matrix. */
class KotlinWatchesCompilationTest : AbstractExpressionCompilationTest() {
    private val kotlin = SnapshotBreakpoint.EXPRESSION_LANGUAGE_KOTLIN

    @Test
    fun `single and few watches`() {
        val locals = listOf(reference("name", String::class.java, "Davis"), int("page", 1))
        assertWatches(kotlin, listOf("name"), listOf("Davis"), locals)
        assertWatches(kotlin, listOf("name", "page", "page + 1"), listOf("Davis", 1, 2), locals)
    }

    @Test
    fun `primitive watches`() {
        val locals = listOf(
            int("i", 42), long("l", 100L), double("d", 1.5), float("f", 2.5f),
            char("c", 'x'), boolean("b", true),
        )
        assertWatches(kotlin, listOf("i", "l", "d", "f", "c", "b"), listOf(42, 100L, 1.5, 2.5f, 'x', true), locals)
    }

    @Test
    fun `string property and method watches`() {
        val locals = listOf(reference("s", String::class.java, "hello"))
        assertWatches(
            kotlin,
            listOf("s.length", "s[0]", "s.contains(\"ell\")", "s.startsWith(\"he\")"),
            listOf(5, 'h', true, true),
            locals,
        )
    }

    @Test
    fun `arithmetic and comparison watches`() {
        val locals = listOf(int("x", 7), int("y", 3))
        assertWatches(
            kotlin,
            listOf("x + y", "x - y", "x * y", "x % y", "x > y"),
            listOf(10, 4, 21, 1, true),
            locals,
        )
    }

    @Test
    fun `null and safe call watches`() {
        val locals = listOf(reference("value", String::class.java, "hello"), int("page", 1))
        assertWatches(kotlin, listOf("value", "null", "value?.length", "page + 1"), listOf("hello", null, 5, 2), locals)
    }

    @Test
    fun `field and method watches`() {
        val receiver = ExpressionTarget("secret", 10, true)
        assertWatches(
            kotlin,
            listOf("secret", "count", "doubled()"),
            listOf("secret", 10, 20),
            receiver = receiver,
        )
    }

    @Test
    fun `nullable watches`() {
        val receiver = KotlinExpressionTarget("secret", 5, KotlinExpressionNode("leaf", 7, null))
        assertWatches(
            kotlin,
            listOf("nullableNode?.value", "nullableNode?.text()"),
            listOf(7, "leaf"),
            receiver = receiver,
        )
    }

    @Test
    fun `getter-only property watch`() {
        val receiver = KotlinExpressionTarget("secret", 5, KotlinExpressionNode("leaf", 7, null))
        assertWatches(kotlin, listOf("doubled"), listOf(10), receiver = receiver)
    }

    @Test
    fun `enum watches`() {
        val locals = listOf(reference("status", ExpressionStatus::class.java, ExpressionStatus.ACTIVE))
        assertWatches(kotlin, listOf("status", "status == ExpressionStatus.ACTIVE"), listOf(ExpressionStatus.ACTIVE, true), locals)
    }

    @Test
    fun `many watches`() {
        val locals = listOf(reference("name", String::class.java, "Davis"), int("page", 1))
        assertWatches(
            kotlin,
            listOf(
                "name", "page", "page + 1", "page * 2", "page - 1", "name.length",
                "page > 0", "page < 0", "name == \"Davis\"", "null",
            ),
            listOf("Davis", 1, 2, 2, 0, 5, true, false, true, null),
            locals,
        )
    }
}
