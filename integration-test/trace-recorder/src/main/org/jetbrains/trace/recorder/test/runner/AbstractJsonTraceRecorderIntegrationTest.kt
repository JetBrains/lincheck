/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.trace.recorder.test.runner

import AbstractGradleTraceIntegrationTest
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.writeText

abstract class AbstractJsonTraceRecorderIntegrationTest(
    override val projectPath: String,
    val checkRepresentationByDefault: Boolean,
) : AbstractGradleTraceIntegrationTest() {
    override val formatArgs: Map<String, String> = mapOf("format" to "binary", "formatOption" to "stream")

    data class TestCase(
        val className: String,
        val methodName: String,
        val gradleCommand: String,
        val jvmArgs: List<String>,
        val checkRepresentation: Boolean? = null,
        val reasonForMuting: String? = null,
    )

    open fun test(testCase: TestCase) = runTest(
        testClassName = testCase.className,
        testMethodName = testCase.methodName,
        extraJvmArgs = testCase.jvmArgs,
        commands = listOf(testCase.gradleCommand),
        checkRepresentation = testCase.checkRepresentation ?: checkRepresentationByDefault,
    )

    companion object {
        private fun getAllTestCases(resourcePath: String): Collection<TestCase> {
            val json = loadResourceText(resourcePath, this::class.java)
            val entries = parseJsonEntries(json)
            return entries.flatMap { jsonEntry ->
                jsonEntry.methods.map {
                    TestCase(
                        className = jsonEntry.className,
                        methodName = it,
                        gradleCommand = jsonEntry.gradleCommand,
                        jvmArgs = jsonEntry.jvmArgs,
                        checkRepresentation = jsonEntry.checkRepresentation,
                        reasonForMuting = jsonEntry.reasonsForMuting[it]
                    )
                }
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

        private fun renderSingleTestCode(
            testCase: TestCase,
            abstractTestClass: String,
            timeoutMinutes: Long,
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

            val disabledString = testCase.reasonForMuting?.let { "@Disabled(${it.toLiteral()})\n" } ?: ""

            return disabledString + """
                @Nested
                inner class $generatedTestClassName : $abstractTestClass() {
                    @Test
                    @Timeout($timeoutMinutes * 60)
                    fun test() {
                        runTest(
                            testClassName = ${testCase.className.toLiteral()},
                            testMethodName = ${testCase.methodName.toLiteral()},
                            extraJvmArgs = listOf(${testCase.jvmArgs.joinToString { it.toLiteral() }}),
                            commands = listOf(${testCase.gradleCommand.toLiteral()}),
                            checkRepresentation = ${testCase.checkRepresentation} ?: ${AbstractJsonTraceRecorderIntegrationTest::checkRepresentationByDefault.name},
                        )
                    }
                }
                """.trimIndent()
        }

        private fun renderTestGroup(
            testCases: Collection<TestCase>,
            abstractTestClass: String,
            timeoutMinutes: Long,
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
                    renderTestGroup(cases, abstractTestClass, timeoutMinutes, outerClassNamesToIgnore + 1)
                val simpleClassName = className.substringAfterLast('.')
                val classNameToUse = if (simpleClassName in clashedSimpleClassNames) className else simpleClassName
                val innerClassBody = subResult.prependIndent(" ".repeat(4))
                "@Nested\ninner class ${makeClassName(listOf(classNameToUse))} {\n$innerClassBody\n}"
            }

            val singleTestCases = testCases.map {
                renderSingleTestCode(it, abstractTestClass, timeoutMinutes, outerClassNamesToIgnore)
            }

            return (singleTestCases + subGroupResults.values).joinToString("\n\n")
        }

        fun renderAllTestsCode(
            groupName: String,
            resourcePath: String,
            abstractTestClass: String,
            packageName: String,
            customImports: List<String>,
            timeoutMinutes: Long,
        ): String {
            val testCases = getAllTestCases(resourcePath)

            val addIgnore = testCases.any { it.reasonForMuting != null }

            val beforeCustomImports = """
                @file:Suppress("ClassName", "FunctionName")
                
                package $packageName
                
                import org.jetbrains.trace.recorder.test.runner.*
                import org.junit.jupiter.api.Nested
                import org.junit.jupiter.api.Test
                import org.junit.jupiter.api.Timeout
                """.trimIndent() +
                    if (addIgnore) "\nimport org.junit.jupiter.api.Disabled" else ""

            val disclaimer = """
                /*
                  THIS FILE IS AUTOGENERATED!
                  DO NOT MODIFY!
                */
                """.trimIndent()

            val renderedTestCases = renderTestGroup(testCases, abstractTestClass, timeoutMinutes, 0)
                .prependIndent(" ".repeat(4))

            return "$beforeCustomImports${customImports.joinToString("\n")}\n\n$disclaimer\n\nclass ${groupName}TraceRecorderJsonIntegrationTests {\n$renderedTestCases\n}\n"
        }
    }
}

sealed class TestGenerator(
    val groupName: String,
    private val resourcePath: String,
    private val abstractTestClass: String,
    private val packageName: String,
    private val customImports: List<String> = listOf(),
    private val timeoutMinutes: Long = 20,
) {
    fun generateString(): String = AbstractJsonTraceRecorderIntegrationTest.renderAllTestsCode(
        groupName, resourcePath, abstractTestClass, packageName, customImports, timeoutMinutes
    )

    fun generateFile(path: Path) {
        path.writeText(generateString())
    }
}
