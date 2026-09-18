/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.fixtures

open class KotlinExpressionBase {
    private val inheritedSecret = "base"
    protected fun inheritedMatches(value: String) = inheritedSecret == value
    protected open fun inheritedEnabled() = false
    protected open val inheritedProperty = false
}

class KotlinExpressionTarget(
    private val secret: String,
    val count: Int,
    private val nullableNode: KotlinExpressionNode?,
) : KotlinExpressionBase() {
    private val doubled: Int get() = count * 2
    private val node = KotlinExpressionNode("root", 5, KotlinExpressionNode("leaf", 9, null))

    private fun hasSecret(expected: Any?) = secret == expected
    private fun acceptsNode(value: KotlinExpressionNode) = value.value == 5
    private fun acceptsNullableInt(value: Int?) = value == 5
    private fun overloaded(value: Int) = value + count
    private fun overloaded(value: String) = value + secret
    protected override fun inheritedEnabled() = true
    protected override val inheritedProperty = true

    companion object {
        private val mode: String get() = "prod"
        private fun isProduction() = mode == "prod"
    }
}

class KotlinExpressionNode(
    private val text: String,
    val value: Int,
    private val next: KotlinExpressionNode?,
) {
    private fun text() = text
    private fun next() = next
}

fun String.expressionSuffix() = "$this!"
val String.expressionFirst: Char get() = first()
fun expressionTopLevel(value: Int, expected: Int = 5) = value == expected
