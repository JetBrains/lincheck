/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.impl

import org.jetbrains.trace.recorder.test.runner.AbstractTraceRecorderIntegrationTest
import org.jetbrains.trace.recorder.test.runner.loadResourceText
import org.jetbrains.trace.recorder.test.runner.parseJsonEntries
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.writeText

abstract class AbstractJsonTraceRecorderIntegrationTest(
    override val projectPath: String,
) : AbstractTraceRecorderIntegrationTest() {
    override val formatArgs: Map<String, String> = mapOf("format" to "binary", "formatOption" to "stream")

    data class TestCase(
        val className: String,
        val methodName: String,
        val gradleCommand: String,
        val jvmArgs: List<String>,
        val checkRepresentation: Boolean,
    )

    open fun test(testCase: TestCase) = runTest(
        testClassName = testCase.className,
        testMethodName = testCase.methodName,
        extraJvmArgs = testCase.jvmArgs,
        commands = listOf(testCase.gradleCommand),
        checkRepresentation = testCase.checkRepresentation,
    )

    companion object {
        fun getAllTestCases(resourcePath: String): Collection<TestCase> {
            val json = loadResourceText(resourcePath, this::class.java)
            val entries = parseJsonEntries(json)
            return entries.flatMap { (className, gradleCommand, methods, jvmArgs, checkRepresentation) ->
                methods.map { TestCase(className, it, gradleCommand, jvmArgs, checkRepresentation) }
            }
        }

        private fun makeClassName(pieces: List<String>) = pieces
            .joinToString("_")
            .replaceFirstChar { it.titlecase(Locale.ENGLISH) }
            .replace('.', '_')
            .replace(' ', '_')
            .let { name ->
                val isValidIdentifier = name.isNotEmpty() && name.drop(1).all { it.isJavaIdentifierPart() } &&
                        name.first().isJavaIdentifierStart()
                if (isValidIdentifier) name else "`$name`"
            }

        private fun String.toSimpleName() = substringAfterLast('.')

        fun renderSingleTestCode(
            testCase: TestCase,
            abstractTestClass: String,
            timeoutMinutes: Long,
            category: String?,
            classNamesToIgnore: Int,
        ): String {
            val formattedOriginalClassNamePieces = testCase.className.toSimpleName()
                .split('$').drop(classNamesToIgnore)

            val generatedTestClassName = makeClassName(formattedOriginalClassNamePieces + testCase.methodName)

            fun String.toLiteral(): String {
                val dollarsCount = generateSequence("") { "$it$" }.takeWhile { it in this }.last().length
                val dollarPrefix = if (dollarsCount == 0) "" else "$".repeat(dollarsCount + 1)
                return "$dollarPrefix\"$this\""
            }

            val categoryAnnotation = category?.let { "@Category($it::class)\n" } ?: ""
            val testName = makeClassName(testCase.className.toSimpleName().split('$') + testCase.methodName)

            return categoryAnnotation + """
                class $generatedTestClassName : $abstractTestClass() {
                    @Test(timeout = $timeoutMinutes * 60 * 1000L)
                    fun $testName() {
                        test(
                            TestCase(
                                className = ${testCase.className.toLiteral()},
                                methodName = ${testCase.methodName.toLiteral()},
                                jvmArgs = listOf(${testCase.jvmArgs.joinToString { it.toLiteral() }}),
                                gradleCommand = ${testCase.gradleCommand.toLiteral()},
                                checkRepresentation = ${testCase.checkRepresentation},
                            )
                        )
                    }
                }
                """.trimIndent()
        }

        private fun renderTestGroup(
            testCases: Collection<TestCase>,
            abstractTestClass: String,
            timeoutMinutes: Long,
            category: String?,
            outerClassNamesToIgnore: Int,
        ): String {
            fun TestCase.classNameSuffix(): String {
                val parts = className.split('$')
                return parts.drop(outerClassNamesToIgnore).joinToString("$")
            }

            val subGroups = testCases
                .filter { it.classNameSuffix().isNotEmpty() }
                .groupBy { it.classNameSuffix().substringBefore('$') }

            val testCases = testCases.filter { it.classNameSuffix().isEmpty() }

            val clashedSimpleClassNames = subGroups.keys.map { it.toSimpleName() }.groupBy { it }.filter { it.value.size > 1 }.keys

            val subGroupResults = subGroups.mapValues { (className, cases) ->
                val subResult =
                    renderTestGroup(cases, abstractTestClass, timeoutMinutes, category, outerClassNamesToIgnore + 1)
                val simpleClassName = className.substringAfterLast('.')
                val classNameToUse = if (simpleClassName in clashedSimpleClassNames) className else simpleClassName
                val categoryAnnotation = category?.let { "@Category($it::class)\n" } ?: ""
                val innerClassBody = subResult.prependIndent(" ".repeat(4))
                "@RunWith(Enclosed::class)\n${categoryAnnotation}class ${makeClassName(listOf(classNameToUse))} {\n$innerClassBody\n}"
            }

            val singleTestCases = testCases.map {
                renderSingleTestCode(it, abstractTestClass, timeoutMinutes, category, outerClassNamesToIgnore)
            }

            return (singleTestCases + subGroupResults.values).joinToString("\n\n")
        }

        fun renderAllTestsCode(
            groupName: String,
            resourcePath: String,
            abstractTestClass: String,
            packageName: String,
            category: String?,
            customImports: List<String>,
            timeoutMinutes: Long,
        ): String {
            val testCases = getAllTestCases(resourcePath)

            val beforeCustomImports = """
                @file:Suppress("ClassName", "FunctionName")
                
                package $packageName
                
                import org.jetbrains.trace.recorder.test.impl.*
                import org.jetbrains.trace.recorder.test.runner.*
                import org.junit.Test
                import org.junit.experimental.categories.Category
                import org.junit.experimental.runners.Enclosed
                import org.junit.runner.RunWith
                """.trimIndent()

            val disclaimer = """
                /*
                  THIS FILE IS AUTOGENERATED!
                  DO NOT MODIFY!
                */
                """.trimIndent()

            val renderedTestCases = renderTestGroup(testCases, abstractTestClass, timeoutMinutes, category, 0)
                .prependIndent(" ".repeat(4))

            return "$beforeCustomImports${customImports.joinToString("\n")}\n\n$disclaimer\n\n@RunWith(Enclosed::class)\nclass $groupName {\n$renderedTestCases\n}\n"
        }
    }
}

sealed class TestGenerator(
    val groupName: String,
    private val resourcePath: String,
    private val abstractTestClass: String,
    private val packageName: String,
    private val category: String?,
    private val customImports: List<String> = listOf(),
    private val timeoutMinutes: Long = 20,
) {
    fun generateString(): String = AbstractJsonTraceRecorderIntegrationTest.renderAllTestsCode(
        groupName, resourcePath, abstractTestClass, packageName, category, customImports, timeoutMinutes
    )

    fun generateFile(path: Path) {
        path.writeText(generateString())
    }
}
