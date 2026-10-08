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
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.KotlinExpressionNode
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.KotlinCatalog
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.KotlinExpressionTarget
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.KotlinOuter
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.kotlinHidden
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.kotlinHiddenType
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.junit.Test

/** Agent-side counterpart of the IDE Kotlin condition compilation matrix. */
class KotlinConditionCompilationTest : AbstractExpressionCompilationTest() {
    private val kotlin = SnapshotBreakpoint.EXPRESSION_LANGUAGE_KOTLIN

    @Test
    fun `primitive comparisons`() {
        assertCondition(kotlin, "d > 1.0", true, listOf(double("d", 1.5)))
        assertCondition(kotlin, "i == 42", true, listOf(int("i", 42)))
        assertCondition(kotlin, "l < 200L", true, listOf(long("l", 100L)))
        assertCondition(kotlin, "f >= 2.5f", true, listOf(float("f", 2.5f)))
        assertCondition(kotlin, "c == 'x'", true, listOf(char("c", 'x')))
        assertCondition(kotlin, "enabled", true, listOf(boolean("enabled", true)))
    }

    @Test
    fun `string operations and null checks`() {
        val text = reference("text", String::class.java, "hello")
        assertCondition(kotlin, "text == \"hello\"", true, listOf(text))
        assertCondition(kotlin, "text.contains(\"ell\")", true, listOf(text))
        assertCondition(kotlin, "text.length == 5", true, listOf(text))
        assertCondition(kotlin, "text.startsWith(\"he\")", true, listOf(text))
        assertCondition(kotlin, "missing == null", true, listOf(reference("missing", String::class.java, null)))
    }

    @Test
    fun `logical arithmetic range and multiple captures`() {
        val locals = listOf(int("x", 7), int("y", 3), boolean("ready", true))
        assertCondition(kotlin, "ready && x > y", true, locals)
        assertCondition(kotlin, "!ready || x + y == 10", true, locals)
        assertCondition(kotlin, "x * y == 21 && x % y == 1", true, locals)
        assertCondition(kotlin, "x in 1..10", true, locals)
        assertCondition(kotlin, "x < y", false, locals)
    }

    @Test
    fun `type checks smart casts safe calls and arrays`() {
        val value = reference("value", Any::class.java, "hello")
        assertCondition(kotlin, "value is String", true, listOf(value))
        assertCondition(kotlin, "value is String && value.length == 5", true, listOf(value))
        assertCondition(kotlin, "value?.toString()?.length == 5", true, listOf(value))
        assertCondition(kotlin, "values[1] == 4", true, listOf(reference("values", IntArray::class.java, intArrayOf(2, 4))))
    }

    @Test
    fun `private and public enclosing properties`() {
        val receiver = ExpressionTarget("s3cr3t", 2, true)
        assertCondition(kotlin, "secret == \"s3cr3t\"", true, receiver = receiver)
        assertCondition(kotlin, "enabled && count == 2", true, receiver = receiver)
        assertCondition(kotlin, "this.secret == \"s3cr3t\"", true, receiver = receiver)
        assertCondition(kotlin, "this === self()", true, receiver = receiver)
    }

    @Test
    fun `private enclosing methods and overloads`() {
        val receiver = ExpressionTarget("key", 4, true)
        assertCondition(kotlin, "hasSecret(\"key\")", true, receiver = receiver)
        assertCondition(kotlin, "add(2, 3) == 5", true, receiver = receiver)
        assertCondition(kotlin, "overloaded(2) == 6", true, receiver = receiver)
        assertCondition(kotlin, "overloaded(\"pre-\") == \"pre-key\"", true, receiver = receiver)
    }

    @Test
    fun `inherited members`() {
        val kotlinReceiver = KotlinExpressionTarget("key", 4, null)
        assertCondition(kotlin, "inheritedMatches(\"base\")", true, receiver = kotlinReceiver)
    }

    @Test
    fun `overridden inherited method`() {
        val receiver = KotlinExpressionTarget("key", 4, null)
        assertCondition(kotlin, "inheritedEnabled()", true, receiver = receiver)
    }

    @Test
    fun `overridden inherited property`() {
        val receiver = KotlinExpressionTarget("key", 4, null)
        assertCondition(kotlin, "inheritedProperty", true, receiver = receiver)
    }

    @Test
    fun `varargs members`() {
        val receiver = ExpressionTarget("key", 4, true)
        assertCondition(kotlin, "hasPrefix(\"a\", \"b\")", true, receiver = receiver)
    }

    @Test
    fun `receiver independent expression in nested class`() {
        assertCondition(
            kotlin,
            "id > 0",
            true,
            locals = listOf(int("id", 1)),
            enclosingType = ExpressionTarget.Nested::class.java,
        )
    }

    @Test
    fun `private member chains on captured application objects`() {
        val node = ExpressionNode("leaf", 5, ExpressionNode("next", 9, null))
        val local = reference("node", ExpressionNode::class.java, node)
        assertCondition(kotlin, "node.text == \"leaf\" && node.value == 5", true, listOf(local))
        assertCondition(kotlin, "node.text() == \"leaf\"", true, listOf(local))
        assertCondition(kotlin, "node.next().value == 9", true, listOf(local))
    }

    @Test
    fun `Kotlin overloads and typed private members`() {
        val receiver = KotlinExpressionTarget("key", 4, KotlinExpressionNode("nullable", 5, null))
        assertCondition(kotlin, "hasSecret(\"key\" as Any)", true, receiver = receiver)
        assertCondition(kotlin, "overloaded(2) == 6", true, receiver = receiver)
        assertCondition(kotlin, "overloaded(\"pre-\") == \"pre-key\"", true, receiver = receiver)
        assertCondition(kotlin, "acceptsNode(node)", true, receiver = receiver)
    }

    @Test
    fun `Kotlin getter-only property`() {
        val receiver = KotlinExpressionTarget("key", 4, KotlinExpressionNode("nullable", 5, null))
        assertCondition(kotlin, "doubled == 8", true, receiver = receiver)
    }

    @Test
    fun `Kotlin nullable boxed parameter`() {
        val receiver = KotlinExpressionTarget("key", 4, KotlinExpressionNode("nullable", 5, null))
        assertCondition(kotlin, "acceptsNullableInt(node.value)", true, receiver = receiver)
    }

    @Test
    fun `Kotlin chains safe calls and scope functions`() {
        val receiver = KotlinExpressionTarget("key", 4, KotlinExpressionNode("nullable", 5, null))
        assertCondition(kotlin, "node.next()?.text() == \"leaf\"", true, receiver = receiver)
        assertCondition(kotlin, "nullableNode?.value == 5", true, receiver = receiver)
        assertCondition(kotlin, "nullableNode?.let { it.value == 5 } == true", true, receiver = receiver)
        assertCondition(kotlin, "run { secret == \"key\" && this.count == 4 }", true, receiver = receiver)
    }

    @Test
    fun `Kotlin top level extensions and functions`() {
        val receiver = KotlinExpressionTarget("key", 4, null)
        val local = reference("text", String::class.java, "hello")
        assertCondition(kotlin, "text.expressionSuffix() == \"hello!\"", true, listOf(local), receiver)
        assertCondition(kotlin, "text.expressionFirst == 'h'", true, listOf(local), receiver)
        assertCondition(kotlin, "expressionTopLevel(count)", false, receiver = receiver)
    }

    @Test
    fun `Kotlin companion members`() {
        val receiver = KotlinExpressionTarget("key", 4, null)
        assertCondition(kotlin, "mode == \"prod\" && isProduction()", true, receiver = receiver)
    }

    @Test
    fun `enum constants`() {
        val status = reference("status", ExpressionStatus::class.java, ExpressionStatus.ACTIVE)
        assertCondition(kotlin, "status == ExpressionStatus.ACTIVE", true, listOf(status))
    }

    @Test
    fun `top-level function called from its own file facade`() {
        val text = reference("text", String::class.java, "hello")
        val fileFacade = Class.forName(KotlinExpressionTarget::class.java.name.substringBeforeLast('.') + ".KotlinExpressionTargetKt")
        assertCondition(kotlin, "expressionIsLong(text)", true, listOf(text), enclosingType = fileFacade)
    }

    @Test
    fun `enclosing receiver in a nested class`() {
        assertCondition(kotlin, "value > limit", true, listOf(int("value", 15)), receiver = KotlinOuter.Inner(10))
        assertCondition(kotlin, "value > limit", false, listOf(int("value", 5)), receiver = KotlinOuter.Inner(10))
    }

    @Test
    fun `private method with a nested class parameter`() {
        val entry = reference("entry", KotlinCatalog.Entry::class.java, KotlinCatalog.Entry(1))
        assertCondition(kotlin, "rate(entry) > value", true, listOf(entry, int("value", 3)), receiver = KotlinCatalog(5))
    }

    @Test
    fun `boxed capture passed to a nullable parameter`() {
        val receiver = KotlinExpressionTarget("key", 4, null)
        assertCondition(kotlin, "acceptsNullableInt(maybe)", true, listOf(reference("maybe", Integer::class.java, 5)), receiver)
        assertCondition(kotlin, "acceptsNullableInt(maybe)", false, listOf(reference("maybe", Integer::class.java, null)), receiver)
    }

    @Test
    fun `inherited private property through the enclosing receiver`() {
        assertCondition(kotlin, "inheritedSecret == \"base\"", true, receiver = KotlinExpressionTarget("key", 4, null))
    }

    @Test
    fun `members of a package-private class`() {
        val hidden = reference("hidden", kotlinHiddenType(), kotlinHidden(4))
        assertCondition(kotlin, "hidden.id == 4 && hidden.twice() == 8", true, listOf(hidden))
    }

    @Test
    fun `capture typed as the enclosing class`() {
        val target = KotlinExpressionTarget("key", 4, null)
        val ref = reference("ref", KotlinExpressionTarget::class.java, target)
        assertCondition(kotlin, "value > ref.count", true, listOf(int("value", 5), ref))
    }
}
