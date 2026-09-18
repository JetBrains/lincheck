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

import org.jetbrains.lincheck.jvm.agent.LocalVariableInfo
import org.jetbrains.lincheck.jvm.agent.expressions.java.JavaExpressionToolchain
import org.jetbrains.lincheck.jvm.agent.expressions.java.JavaSource
import org.jetbrains.lincheck.jvm.agent.expressions.kotlin.KotlinExpressionToolchain
import org.jetbrains.lincheck.jvm.agent.fixtures.JavaChainedCallShapeFixture
import org.jetbrains.lincheck.jvm.agent.transformWithSnapshotBreakpoints
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import sun.nio.ch.lincheck.BreakpointStorage
import java.io.ByteArrayInputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Tests compiler bootstrap and shared agent-side expression compilation infrastructure. */
class ExpressionCompilerTest {

    @Test
    fun `embedded Java compiler is available`() {
        assertTrue(
            "The runtime-compatible embedded compiler should load",
            JavaExpressionToolchain.isEmbeddedCompilerAvailable,
        )
    }

    @Test
    fun `embedded Java compiler compiles expressions on Java 8`() {
        assumeTrue("This regression test runs on Java 8 only", isJava8())

        val classes = JavaExpressionToolchain.compile(
            sources = listOf(
                JavaSource(
                    "Expression",
                    "public class Expression { public static boolean evaluate(int value) { return value > 0; } }",
                ),
            ),
            classpath = expressionClasspath(classLoader = null),
            systemCompiler = null,
        ).classes

        assertTrue("The embedded compiler should produce Expression.class", "Expression" in classes)
    }

    @Test
    fun `embedded Kotlin compiler is available`() {
        val classes = KotlinExpressionToolchain.compile(
            mapOf("Expression.kt" to "class Expression"),
            expressionClasspath(classLoader = null),
        )

        assertTrue("The embedded compiler should produce Expression.class", "Expression" in classes)
    }

    @Test
    fun `Kotlin PSI distinguishes calls from other referenced names`() {
        val names = KotlinExpressionToolchain.expressionNames(
            listOf("owner.child == \"ignored.name\" && owner.isProduction()"),
        )

        assertEquals(setOf("owner", "child", "isProduction"), names.referenced)
        assertEquals(setOf("isProduction"), names.called)
    }

    @Test
    fun `capture selection includes only referenced locals and excludes the receiver`() {
        val locals = listOf(
            local("id", Type.getType(Integer::class.java)),
            local("unrelated", Type.INT_TYPE),
            local("this", Type.getObjectType("com/example/Owner")),
        )

        val captures = ExpressionWrapper.selectCaptures(listOf("id > 0 && this.active"), locals)

        assertEquals(listOf("id"), captures.map { it.name })
    }

    @Test
    fun `expression classpath includes file URLs from the target loader`() {
        val directory = Files.createTempDirectory("expression-classpath-").toFile()
        try {
            URLClassLoader(arrayOf(directory.toURI().toURL()), null).use { loader ->
                assertTrue(directory in expressionClasspath(loader).entries)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Java compiler resolves classes exposed only as loader resources`() {
        val dependencyName = "resource.only.JavaDependency"
        val baseName = "resource.only.JavaBase"
        val leafName = "resource.only.JavaLeaf"
        val loader = ResourceOnlyClassLoader(
            mapOf(
                dependencyName to emptyClass(dependencyName, baseName, leafName),
                baseName to emptyClass(baseName),
                leafName to emptyClass(leafName),
            ),
        )

        val classes = expressionClasspath(loader, requiredClassNames = setOf(dependencyName)).use { classpath ->
            JavaExpressionToolchain.compile(
                sources = listOf(
                    JavaSource(
                        "Expression",
                        "public class Expression { " +
                            "public $leafName evaluate($dependencyName dependency) { return dependency.leaf; } }",
                    ),
                ),
                classpath = classpath,
            ).classes
        }

        assertTrue("The Java compiler should resolve the resource-only class", "Expression" in classes)
    }

    @Test
    fun `Kotlin compiler resolves classes exposed only as loader resources`() {
        val dependencyName = "resource.only.KotlinDependency"
        val loader = ResourceOnlyClassLoader(mapOf(dependencyName to emptyClass(dependencyName)))

        val classes = expressionClasspath(loader, requiredClassNames = setOf(dependencyName)).use { classpath ->
            KotlinExpressionToolchain.compile(
                sources = mapOf("Expression.kt" to "class Expression(val dependency: $dependencyName)"),
                classpath = classpath,
            )
        }

        assertTrue("The Kotlin compiler should resolve the resource-only class", "Expression" in classes)
    }

    @Test
    fun `a compilation failure blocks the breakpoint`() {
        val fixture = JavaChainedCallShapeFixture::class.java
        val breakpoint = SnapshotBreakpoint(
            uuid = UUID.randomUUID(),
            className = fixture.name,
            fileName = "JavaChainedCallShapeFixture.java",
            lineNumber = 33,
            expressionLanguage = SnapshotBreakpoint.EXPRESSION_LANGUAGE_JAVA,
            conditionSource = "this is not a valid java expression !!!",
        )

        transformWithSnapshotBreakpoints(fixture.name.replace('.', '/'), listOf(breakpoint))

        assertTrue(
            "The compile failure must reach the breakpoint-blocked channel, got=$blockedReasons",
            blockedReasons.any { it.contains("failed to compile", ignoreCase = true) },
        )
    }

    private fun local(name: String, type: Type) = LocalVariableInfo(
        name = name,
        index = 0,
        type = type,
        labelIndexRange = Label() to Label(),
        localKind = org.jetbrains.lincheck.descriptors.LocalKind.VARIABLE,
    )

    private fun isJava8() = System.getProperty("java.specification.version")
        .removePrefix("1.")
        .substringBefore('.') == "8"

    private class ResourceOnlyClassLoader(classes: Map<String, ByteArray>) : ClassLoader(null) {
        private val resources = classes.mapKeys { (binaryName, _) -> binaryName.replace('.', '/') + ".class" }

        override fun getResourceAsStream(name: String) = resources[name]?.let(::ByteArrayInputStream)
    }

    private fun emptyClass(
        binaryName: String,
        superclassBinaryName: String = "java.lang.Object",
        fieldBinaryName: String? = null,
    ): ByteArray =
        ClassWriter(0).apply {
            visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
                binaryName.replace('.', '/'),
                null,
                superclassBinaryName.replace('.', '/'),
                null,
            )
            fieldBinaryName?.let {
                visitField(
                    Opcodes.ACC_PUBLIC,
                    "leaf",
                    Type.getObjectType(it.replace('.', '/')).descriptor,
                    null,
                    null,
                ).visitEnd()
            }
            visitEnd()
        }.toByteArray()

    companion object {
        private val blockedReasons = CopyOnWriteArrayList<String>()

        @JvmStatic
        @BeforeClass
        fun captureBlockedNotifications() {
            BreakpointStorage.setOnBreakpointBlocked { _, _, reason -> blockedReasons += reason.toString() }
        }

        @JvmStatic
        @AfterClass
        fun releaseBlockedNotifications() {
            BreakpointStorage.setOnBreakpointBlocked(null)
        }
    }
}
