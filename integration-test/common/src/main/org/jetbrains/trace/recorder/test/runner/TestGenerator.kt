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

import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.writeText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

abstract class TestGenerator(
    val groupName: String,
    private val resourcePath: String,
    private val abstractTestClass: String,
    private val packageName: String,
    private val customImports: List<String> = listOf(),
    private val timeoutMinutes: Long = 20,
    private val generatorMainClass: String = "org.jetbrains.trace.recorder.test.runner.GenerateTestsKt.main",
) {
    fun generateString(): String = renderAllTestsCode(
        groupName, resourcePath, abstractTestClass, packageName, customImports, timeoutMinutes
    )

    fun generateFile(path: Path) {
        path.writeText(generateString())
    }

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
                    traceShouldContain = jsonEntry.traceShouldContain,
                    reasonForMuting = jsonEntry.reasonsForMuting[it],
                    breakpoints = jsonEntry.breakpoints?.let { convertBreakpointsToIni(it) }
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

        // Embed the INI content as an escaped single-line Kotlin string literal.
        // A raw triple-quoted literal would inject newlines into the surrounding trimIndent()
        // template and carry the surrounding source indentation into the runtime value.
        val breakpointsArg = testCase.breakpoints?.toKotlinStringLiteral() ?: "null"

        return disabledString + """
                @Nested
                inner class $generatedTestClassName : $abstractTestClass() {
                    @Test
                    @Timeout($timeoutMinutes * 60)
                    fun test() = runTest(
                        testClassName = ${testCase.className.toLiteral()},
                        testMethodName = ${testCase.methodName.toLiteral()},
                        extraJvmArgs = listOf(${testCase.jvmArgs.joinToString { it.toLiteral() }}),
                        commands = listOf(${testCase.gradleCommand.toLiteral()}),
                        checkRepresentation = ${testCase.checkRepresentation},
                        traceShouldContain = listOf(${testCase.traceShouldContain.joinToString { it.toLiteral() }}),
                        breakpointsIni = $breakpointsArg
                    )
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

        val clashedSimpleClassNames =
            subGroups.keys.map { it.toSimpleName() }.groupBy { it }.filter { it.value.size > 1 }.keys

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

    private fun renderAllTestsCode(
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
                  Please run `$generatorMainClass` to regenerate.
                */
                """.trimIndent()

        val renderedTestCases = renderTestGroup(testCases, abstractTestClass, timeoutMinutes, 0)
            .prependIndent(" ".repeat(4))

        return "$beforeCustomImports${customImports.joinToString("\n")}\n\n$disclaimer\n\nclass ${groupName}TraceRecorderJsonIntegrationTests {\n$renderedTestCases\n}\n"
    }
}

/**
 * Escapes a string so it can be embedded as a regular (non-raw) Kotlin string literal.
 *
 * Used to emit the `breakpointsIni` argument into generated test files without
 * letting its newlines interact with the surrounding `trimIndent()` / `prependIndent()`
 * layout and without carrying the surrounding source indentation into the runtime value.
 */
internal fun String.toKotlinStringLiteral(): String {
    val escaped = buildString(length + 16) {
        for (c in this@toKotlinStringLiteral) {
            when (c) {
                '\\' -> append("\\\\")
                '"'  -> append("\\\"")
                '$'  -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
    return "\"$escaped\""
}

/**
 * Converts a JSON array of breakpoint definitions into INI file content.
 *
 * Each JSON entry has the form:
 *   { "className": "...", "filePath": "...", "line": N, "condition": null }
 *
 * The resulting INI format is:
 *   [Breakpoint 1]
 *   className = ...
 *   fileName = ...
 *   lineNumber = N
 */
internal fun convertBreakpointsToIni(breakpoints: JsonArray): String {
    return breakpoints.mapIndexed { index, element ->
        val obj = element.jsonObject
        val className = obj["className"]?.jsonPrimitive?.content ?: error("Missing className in breakpoint")
        val filePath = obj["filePath"]?.jsonPrimitive?.content ?: error("Missing filePath in breakpoint")
        val line = obj["line"]?.jsonPrimitive?.content ?: error("Missing line in breakpoint")
        buildString {
            appendLine("[Breakpoint ${index + 1}]")
            appendLine("className = $className")
            appendLine("fileName = $filePath")
            append("lineNumber = $line")
        }
    }.joinToString("\n\n")
}
