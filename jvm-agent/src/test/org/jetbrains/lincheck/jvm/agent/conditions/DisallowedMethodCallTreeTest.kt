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

import org.jetbrains.lincheck.jvm.agent.analysis.*
import org.junit.*
import org.junit.Assert.*

/**
 * Tests for the tree structure representation of [SideEffectViolation.DisallowedMethodCall].
 * Verifies that the toString() method properly formats nested violations as a tree
 * by analyzing actual code with the ConditionSafetyChecker.
 */
class DisallowedMethodCallTreeTest {
    

    @Test
    fun `test simple field write`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            TestCases::class.java.name,
            "simpleFieldWrite",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: simpleFieldWrite
                ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:185
                └── Field write: counter at DisallowedMethodCallTreeTest.kt:185
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test nested method calls with field write`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            TestCases::class.java.name,
            "callsMethodThatWritesField",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: callsMethodThatWritesField
                ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:190
                └── Disallowed method call: simpleFieldWrite
                    ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:185
                    └── Field write: counter at DisallowedMethodCallTreeTest.kt:185
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test multiple violations in same method`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            TestCases::class.java.name,
            "multipleViolations",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: multipleViolations
                ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:195
                ├── Field write: counter at DisallowedMethodCallTreeTest.kt:195
                ├── Field read: array at DisallowedMethodCallTreeTest.kt:196
                └── Array write: at DisallowedMethodCallTreeTest.kt:196
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test deeply nested calls`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            TestCases::class.java.name,
            "level1",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: level1
                ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:211
                └── Disallowed method call: level2
                    ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:206
                    └── Disallowed method call: level3
                        ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:201
                        └── Field write: counter at DisallowedMethodCallTreeTest.kt:201
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test method with mixed violations and nested calls`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            TestCases::class.java.name,
            "complexCase",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: complexCase
                ├── Field read: array at DisallowedMethodCallTreeTest.kt:216
                ├── Array write: at DisallowedMethodCallTreeTest.kt:216
                ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:217
                └── Disallowed method call: callsMethodThatWritesField
                    ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:190
                    └── Disallowed method call: simpleFieldWrite
                        ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:185
                        └── Field write: counter at DisallowedMethodCallTreeTest.kt:185
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test synchronized block violation`() {
        val violation = ConditionSafetyChecker.checkMethodForSideEffects(
            TestCases::class.java.name,
            "useSynchronized",
            "()V",
            this::class.java.classLoader
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: useSynchronized
                ├── Monitor operation (synchronized block) at DisallowedMethodCallTreeTest.kt:222
                ├── Field read: INSTANCE at DisallowedMethodCallTreeTest.kt:224
                ├── Monitor operation (synchronized block) at DisallowedMethodCallTreeTest.kt:222
                └── Monitor operation (synchronized block) at DisallowedMethodCallTreeTest.kt:222
            """.trimIndent(),
            output
        )
    }

    /**
     * Test cases for verifying tree representation.
     */
    private object TestCases {
        @JvmField
        var counter: Int = 0

        @JvmField
        var array: IntArray = IntArray(10)

        @JvmStatic
        fun simpleFieldWrite() {
            counter = 42
        }

        @JvmStatic
        fun callsMethodThatWritesField() {
            simpleFieldWrite()
        }

        @JvmStatic
        fun multipleViolations() {
            counter = 1
            array[0] = 2
        }

        @JvmStatic
        fun level3() {
            counter = 100
        }

        @JvmStatic
        fun level2() {
            level3()
        }

        @JvmStatic
        fun level1() {
            level2()
        }

        @JvmStatic
        fun complexCase() {
            array[0] = 1
            callsMethodThatWritesField()
        }

        @JvmStatic
        fun useSynchronized() {
            synchronized(TestCases::class.java) {
                // synchronized block
            }
        }
    }
}
