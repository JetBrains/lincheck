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
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.NestedHierarchyTarget
import org.jetbrains.lincheck.jvm.agent.expressions.fixtures.PackagePrivateFixtures
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.junit.Test

/** Agent-side counterpart of the IDE Java condition compilation matrix. */
class JavaConditionCompilationTest : AbstractExpressionCompilationTest() {
    private val java = SnapshotBreakpoint.EXPRESSION_LANGUAGE_JAVA

    @Test
    fun `primitive comparisons`() {
        assertCondition(java, "d > 1.0", true, listOf(double("d", 1.5)))
        assertCondition(java, "i == 42", true, listOf(int("i", 42)))
        assertCondition(java, "l < 200L", true, listOf(long("l", 100L)))
        assertCondition(java, "f >= 2.5f", true, listOf(float("f", 2.5f)))
        assertCondition(java, "c == 'x'", true, listOf(char("c", 'x')))
        assertCondition(java, "enabled", true, listOf(boolean("enabled", true)))
    }

    @Test
    fun `string operations and null checks`() {
        val text = reference("text", String::class.java, "hello")
        assertCondition(java, "text.equals(\"hello\")", true, listOf(text))
        assertCondition(java, "text.contains(\"ell\")", true, listOf(text))
        assertCondition(java, "text.length() == 5", true, listOf(text))
        assertCondition(java, "text != null", true, listOf(text))
        assertCondition(java, "missing == null", true, listOf(reference("missing", String::class.java, null)))
    }

    @Test
    fun `logical arithmetic and multiple captures`() {
        val locals = listOf(int("x", 7), int("y", 3), boolean("ready", true))
        assertCondition(java, "ready && x > y", true, locals)
        assertCondition(java, "!ready || x + y == 10", true, locals)
        assertCondition(java, "x * y == 21 && x % y == 1", true, locals)
        assertCondition(java, "x < y", false, locals)
    }

    @Test
    fun `type checks casts ternary and arrays`() {
        val value = reference("value", Any::class.java, "hello")
        assertCondition(java, "value instanceof String", true, listOf(value))
        assertCondition(java, "((String) value).length() == 5", true, listOf(value))
        assertCondition(java, "value == null ? false : true", true, listOf(value))
        assertCondition(java, "values[1] == 4", true, listOf(reference("values", IntArray::class.java, intArrayOf(2, 4))))
    }

    @Test
    fun `private and public enclosing fields`() {
        val receiver = ExpressionTarget("s3cr3t", 2, true)
        assertCondition(java, "secret.equals(\"s3cr3t\")", true, receiver = receiver)
        assertCondition(java, "enabled && count == 2", true, receiver = receiver)
        assertCondition(java, "this.secret.equals(\"s3cr3t\")", true, receiver = receiver)
        assertCondition(java, "this == self()", true, receiver = receiver)
    }

    @Test
    fun `private enclosing methods and overloads`() {
        val receiver = ExpressionTarget("key", 4, true)
        assertCondition(java, "hasSecret(\"key\")", true, receiver = receiver)
        assertCondition(java, "add(2, 3) == 5", true, receiver = receiver)
        assertCondition(java, "overloaded(2) == 6", true, receiver = receiver)
        assertCondition(java, "overloaded(\"pre-\").equals(\"pre-key\")", true, receiver = receiver)
    }

    @Test
    fun `inherited members`() {
        val receiver = ExpressionTarget("key", 4, true)
        assertCondition(java, "inheritedCount == 7 && inheritedMatches(7)", true, receiver = receiver)
    }

    @Test
    fun `varargs members`() {
        val receiver = ExpressionTarget("key", 4, true)
        assertCondition(java, "hasPrefix(\"a\", \"b\")", true, receiver = receiver)
    }

    @Test
    fun `receiver independent expression in nested class`() {
        assertCondition(
            java,
            "id > 0",
            true,
            locals = listOf(int("id", 1)),
            enclosingType = ExpressionTarget.Nested::class.java,
        )
    }

    @Test
    fun `static fields and methods`() {
        assertCondition(java, "MODE.equals(\"prod\")", true)
        assertCondition(java, "LIMIT == 3", true)
        assertCondition(java, "isProduction()", true)
    }

    @Test
    fun `private members on captured application objects`() {
        val node = ExpressionNode("leaf", 5, ExpressionNode("next", 9, null))
        val local = reference("node", ExpressionNode::class.java, node)
        assertCondition(java, "node.text.equals(\"leaf\") && node.value == 5", true, listOf(local))
        assertCondition(java, "node.text().equals(\"leaf\")", true, listOf(local))
        assertCondition(java, "node.next().value == 9", true, listOf(local))
    }

    @Test
    fun `inherited private field condition through a subtype local`() {
        val owner = InheritedExpressionTarget()
        assertCondition(
            java,
            "owner.inheritedSecret.equals(\"base-private\")",
            true,
            locals = listOf(reference("owner", InheritedExpressionTarget::class.java, owner)),
            enclosingType = ExpressionNode::class.java,
        )
    }

    @Test
    fun `qualified private and generic methods`() {
        val receiver = ExpressionTarget("key", 4, true)
        assertCondition(java, "this.enabled()", true, receiver = receiver)
        val value = reference("value", String::class.java, "key")
        val values = reference("values", IntArray::class.java, intArrayOf(2, 4))
        assertCondition(java, "same(value, \"key\")", true, listOf(value), receiver)
        assertCondition(java, "contains(values, 4)", true, listOf(values), receiver)
    }

    @Test
    fun `nested fields and explicit this`() {
        val receiver = ExpressionTarget("key", 4, true)
        assertCondition(java, "this.count == 4", true, receiver = receiver)
        assertCondition(java, "node.next().value == 9", true, receiver = receiver)
        assertCondition(java, "node.next().text().equals(\"leaf\")", true, receiver = receiver)
    }

    @Test
    fun `constructor expression`() {
        val receiver = ExpressionTarget("key", 4, true)
        assertCondition(java, "new ExpressionTarget.Nested(3).value == 3", true, receiver = receiver)
    }

    @Test
    fun `non-static member constructor expression`() {
        val receiver = ExpressionTarget("key", 4, true)
        assertCondition(java, "new Inner(3).value == 3", true, receiver = receiver)
    }

    @Test
    fun `enum constants`() {
        val status = reference("status", ExpressionStatus::class.java, ExpressionStatus.ACTIVE)
        assertCondition(java, "status == ExpressionStatus.ACTIVE", true, listOf(status))
    }

    @Test
    fun `members and constants of package-private classes`() {
        val owner = reference("owner", PackagePrivateFixtures.ownerType(), PackagePrivateFixtures.owner(2, "Davis", true))
        assertCondition(java, "owner.id == 2 && owner.name().equals(\"Davis\")", true, listOf(owner))
        assertCondition(java, "owner.name.equals(\"Davis\")", true, listOf(owner))
        assertCondition(java, "owner.status == PackagePrivateStatus.ACTIVE", true, listOf(owner))
        val status = reference("s", PackagePrivateFixtures.statusType(), PackagePrivateFixtures.activeStatus())
        assertCondition(java, "s == PackagePrivateStatus.ACTIVE", true, listOf(status))
        assertCondition(java, "s == PackagePrivateStatus.INACTIVE", false, listOf(status))
    }

    @Test
    fun `capture typed as the enclosing class`() {
        val target = ExpressionTarget("key", 4, true)
        val ref = reference("ref", ExpressionTarget::class.java, target)
        assertCondition(java, "value > ref.count", true, listOf(int("value", 5), ref))
        assertCondition(java, "ref.doubled() == 8", true, listOf(ref))
    }

    @Test
    fun `enclosing receiver in nested and inner classes`() {
        assertCondition(java, "value > 2", true, receiver = ExpressionTarget.Nested(3))
        assertCondition(java, "this.value == 3", true, receiver = ExpressionTarget("key", 4, true).Inner(3))
    }

    @Test
    fun `inherited private field through the enclosing receiver`() {
        val receiver = InheritedExpressionTarget()
        assertCondition(java, "inheritedSecret.equals(\"base-private\") && count == 10", true, receiver = receiver)
        assertCondition(java, "inheritedSecret().equals(\"base-private\")", true, receiver = receiver)
    }

    @Test
    fun `private methods redeclared along the hierarchy`() {
        assertCondition(java, "token() == 2 && code() == 7", true, receiver = InheritedExpressionTarget())
    }

    @Test
    fun `public members inherited between nested classes`() {
        val owner = reference("owner", NestedHierarchyTarget.Owner::class.java, NestedHierarchyTarget.Owner())
        assertCondition(
            java,
            "owner.email.equals(\"owner@example.com\") && owner.id == 2 && owner.address.city.equals(\"Sun Prairie\")" +
                " && owner.addresses[0].city.equals(\"Sun Prairie\") && owner.addresses.length == 1",
            true,
            listOf(owner),
            enclosingType = NestedHierarchyTarget::class.java,
        )
    }

    @Test
    fun `external superclass with private constructor declared first`() {
        val owner = reference("owner", NestedHierarchyTarget.ExternalOwner::class.java, NestedHierarchyTarget.ExternalOwner())
        assertCondition(
            java,
            "owner.value.equals(\"external-owner\")",
            true,
            listOf(owner),
            enclosingType = NestedHierarchyTarget::class.java,
        )
    }
}
