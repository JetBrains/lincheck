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

import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.jvm.agent.analysis.*
import org.junit.*
import org.junit.Assert.*

/**
 * Tests for the tree structure representation of [SafetyViolation.DisallowedMethodCall].
 * Verifies that the toString() method properly formats nested violations as a tree
 * by analyzing actual code with the SideEffectChecker.
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
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "simpleFieldWrite",
            "()V",
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
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "callsMethodThatWritesField",
            "()V",
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: callsMethodThatWritesField
                └── Disallowed method call: simpleFieldWrite at DisallowedMethodCallTreeTestCases.kt:31
                    └── Field write: counter at DisallowedMethodCallTreeTestCases.kt:26
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test multiple violations in same method`() {
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "multipleViolations",
            "()V",
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
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "level1",
            "()V",
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: level1
                └── Disallowed method call: level2 at DisallowedMethodCallTreeTestCases.kt:52
                    └── Disallowed method call: level3 at DisallowedMethodCallTreeTestCases.kt:47
                        └── Field write: counter at DisallowedMethodCallTreeTestCases.kt:42
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test method with mixed violations and nested calls`() {
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "complexCase",
            "()V",
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        assertEquals(
            """
                Disallowed method call: complexCase
                ├── Array write: at DisallowedMethodCallTreeTestCases.kt:57
                └── Disallowed method call: callsMethodThatWritesField at DisallowedMethodCallTreeTestCases.kt:58
                    └── Disallowed method call: simpleFieldWrite at DisallowedMethodCallTreeTestCases.kt:31
                        └── Field write: counter at DisallowedMethodCallTreeTestCases.kt:26
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test synchronized block violation`() {
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "useSynchronized",
            "()V",
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

    @Test
    fun `test simple loop violation`() {
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "simpleLoop",
            "()V",
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        // Loop only contains local variable writes (which are safe)
        assertEquals(
            """
                Disallowed method call: simpleLoop
                └── Loop detected at DisallowedMethodCallTreeTestCases.kt:71
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test while loop violation`() {
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "whileLoopExample",
            "()V",
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        // Loop only contains local variable writes (which are safe)
        assertEquals(
            """
                Disallowed method call: whileLoopExample
                └── Loop detected at DisallowedMethodCallTreeTestCases.kt:79
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test loop with field write violation`() {
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "loopWithFieldWrite",
            "()V",
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        // Loop only contains local variable writes (which are safe)
        assertEquals(
            """
                Disallowed method call: loopWithFieldWrite
                └── Loop detected at DisallowedMethodCallTreeTestCases.kt:87
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test forEach loop violation`() {
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "forEachLoop",
            "()V",
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        // forEach is compiled to bytecode with iterator calls and array writes
        assertEquals(
            """
                Disallowed method call: forEachLoop
                ├── Array write: at DisallowedMethodCallTreeTestCases.kt:94
                ├── Disallowed method call: iterator at DisallowedMethodCallTreeTestCases.kt:117
                ├── Disallowed method call: hasNext at DisallowedMethodCallTreeTestCases.kt:117
                ├── Disallowed method call: next at DisallowedMethodCallTreeTestCases.kt:117
                ├── Disallowed method call: intValue at DisallowedMethodCallTreeTestCases.kt:117
                └── Loop detected at DisallowedMethodCallTreeTestCases.kt:117
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test repeat loop violation`() {
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "repeatLoop",
            "()V",
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        // repeat contains a loop internally
        assertEquals(
            """
                Disallowed method call: repeatLoop
                └── Loop detected at DisallowedMethodCallTreeTestCases.kt:102
            """.trimIndent(),
            output
        )
    }

    @Test
    fun `test multiple loops violation`() {
        val violation = checkMethodForSideEffects(
            DisallowedMethodCallTreeTestCases::class.java.name,
            "multipleLoops",
            "()V",
        )

        assertNotNull(violation)
        val output = violation!!.toString()

        // Method contains two separate loops
        assertEquals(
            """
                Disallowed method call: multipleLoops
                ├── Loop detected at DisallowedMethodCallTreeTestCases.kt:108
                └── Loop detected at DisallowedMethodCallTreeTestCases.kt:111
            """.trimIndent(),
            output
        )
    }

    private fun checkMethodForSideEffects(
        className: String,
        methodName: String,
        methodDescriptor: String,
    ): SafetyViolation.DisallowedMethodCall? {
        val classLoader = this::class.java.classLoader
        return SideEffectChecker.checkMethodForSideEffects(
            className = className,
            methodName = methodName,
            methodDescriptor = methodDescriptor,
            bytecodeProvider = classLoader::findClassBytecode,
            isClassLoaded = { true },
        )
    }
}
