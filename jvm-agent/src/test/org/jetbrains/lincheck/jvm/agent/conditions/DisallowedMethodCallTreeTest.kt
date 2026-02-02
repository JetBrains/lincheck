/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.conditions

import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import org.jetbrains.lincheck.jvm.agent.analysis.*
import org.junit.*
import org.junit.Assert.*

/**
 * Tests for the tree structure representation of [SideEffectViolation.DisallowedMethodCall].
 * Verifies that the toString() method properly formats nested violations as a tree
 * by analyzing actual code with the ConditionSafetyChecker.
 */
class DisallowedMethodCallTreeTest {

    init {
        DisallowedMethodCallTreeTestCases::class.java
        if (!LincheckInstrumentation.isInitialized) {
            LincheckInstrumentation.attachJavaAgentDynamically()
        }
    }

    @Test
    fun `test simple field write`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "simpleFieldWrite",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: simpleFieldWrite
                └── Field write: counter at DisallowedMethodCallTreeTestCases.kt:26
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test nested method calls with field write`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "callsMethodThatWritesField",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: callsMethodThatWritesField
                └── Disallowed method call: simpleFieldWrite
                    └── Field write: counter at DisallowedMethodCallTreeTestCases.kt:26
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test multiple violations in same method`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "multipleViolations",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: multipleViolations
                ├── Field write: counter at DisallowedMethodCallTreeTestCases.kt:36
                └── Array write: at DisallowedMethodCallTreeTestCases.kt:37
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test deeply nested calls`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "level1",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: level1
                └── Disallowed method call: level2
                    └── Disallowed method call: level3
                        └── Field write: counter at DisallowedMethodCallTreeTestCases.kt:42
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test method with mixed violations and nested calls`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "complexCase",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: complexCase
                ├── Array write: at DisallowedMethodCallTreeTestCases.kt:57
                └── Disallowed method call: callsMethodThatWritesField
                    └── Disallowed method call: simpleFieldWrite
                        └── Field write: counter at DisallowedMethodCallTreeTestCases.kt:26
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test synchronized block violation`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "useSynchronized",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: useSynchronized
                └── Monitor operation (synchronized block) at DisallowedMethodCallTreeTestCases.kt:63
            """.trimIndent(),
            output
        )
    }

}
