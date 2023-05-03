/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario


/**
 * Generates test for this scenario using determined programming language.
 */
internal fun generateTest(scenario: ExecutionScenario, testLanguage: SupportedTestLanguage): String {
    val testGenerator = when (testLanguage) {
        SupportedTestLanguage.KOTLIN -> KotlinExecutionScenarioTestGenerator
        SupportedTestLanguage.JAVA -> JavaExecutionScenarioTestGenerator
    }

    return testGenerator.generateTestCode(scenario)
}

/**
 * Determines programming language used to write the running test using StackTrace
 *
 * @return determined programming language or `null` if can't determine language
 */
internal fun determineTestLanguage(stackTrace: Array<StackTraceElement>): SupportedTestLanguage? {
    // take index of last because in LinCheckerKt there may be many methods with the same name
    val lincheckEntryPointCallIndex = stackTrace.indexOfLast { it.className.startsWith(LINCHECK_PACKAGE_NAME) }
    if (lincheckEntryPointCallIndex == -1) return null // method call not found
    if (lincheckEntryPointCallIndex == stackTrace.lastIndex) {
        return SupportedTestLanguage.KOTLIN // entry point is internal call
    }

    // get the class where lincheck entry point was called
    val testClassName = stackTrace[lincheckEntryPointCallIndex + 1].className
    val testClass = Class.forName(testClassName)

    return if (testClass.isAnnotationPresent(Metadata::class.java)) {
        SupportedTestLanguage.KOTLIN
    } else {
        SupportedTestLanguage.JAVA
    }
}


internal enum class SupportedTestLanguage {
    JAVA,
    KOTLIN
}

internal interface ExecutionScenarioTestGenerator {
    /**
     * Generate custom scenario test code based on the supplied executionScenario
     */
    fun generateTestCode(executionScenario: ExecutionScenario): String
}

internal object KotlinExecutionScenarioTestGenerator : ExecutionScenarioTestGenerator {
    override fun generateTestCode(executionScenario: ExecutionScenario) = TestCodeStringBuilder().apply {
        appendLine(".addCustomScenario {")

        indentBlock {
            appendSequentialPart("initial", executionScenario.initExecution)
            appendParallelPart(executionScenario)
            appendSequentialPart("post", executionScenario.postExecution)
        }

        appendLine("}")
    }.toString()


    private fun TestCodeStringBuilder.appendParallelPart(executionScenario: ExecutionScenario) {
        val parallelPart = executionScenario.parallelExecution
        if (parallelPart.isEmpty()) return

        appendLine("parallel {")
        indentBlock {
            for (threadPart in parallelPart) {
                if (threadPart.isEmpty()) continue

                appendLine("thread {")
                indentBlock {
                    threadPart.forEach { actor -> appendActorCall(actor) }
                }
                appendLine("}")
            }
        }
        appendLine("}")
    }

    private fun TestCodeStringBuilder.appendSequentialPart(
        partName: String,
        actors: MutableList<Actor>
    ) {
        if (actors.isEmpty()) return

        appendLine("$partName {")
        indentBlock {
            actors.forEach { actor -> appendActorCall(actor) }
        }
        appendLine("}")
    }

    private fun TestCodeStringBuilder.appendActorCall(actor: Actor) {
        val methodName = actor.method.name
        val argumentsLine = argumentsToLine(actor.arguments)

        appendLine("actor(::$methodName$argumentsLine)")
    }

    private fun argumentsToLine(arguments: List<Any?>): String {
        if (arguments.isEmpty()) return ""

        return arguments.joinToString(prefix = ", ", separator = ", ") { argumentInstantiationCode(it) }
    }

    private fun argumentInstantiationCode(argument: Any?): String {
        if (argument == null) return "null"

        return when (argument) {
            is Double, Int, Boolean -> "$argument"
            is Byte -> "$argument.toByte()"
            is Short -> "$argument.toShort()"
            is Char -> "${argument.code}.toChar()"
            is String -> "\"$argument\""
            is Float -> "${argument}F"
            is Long -> "${argument}L"
            // other types are not supported for now
            else -> argument.toString()
        }
    }

}

internal object JavaExecutionScenarioTestGenerator : ExecutionScenarioTestGenerator {
    override fun generateTestCode(executionScenario: ExecutionScenario) = TestCodeStringBuilder().apply {
        appendLine(".addCustomScenario(")

        indentBlock(blockIndents = 2) {
            appendLine("new ScenarioBuilder(this.getClass())")

            indentBlock(blockIndents = 2) {
                appendSequentialPart("initial", executionScenario.initExecution)
                appendParallelPart(executionScenario)
                appendSequentialPart("post", executionScenario.postExecution)
            }

            appendLine(".build()")
        }

        appendLine(")")
    }.toString()

    private fun TestCodeStringBuilder.appendParallelPart(executionScenario: ExecutionScenario) {
        val parallelPart = executionScenario.parallelExecution
        if (parallelPart.isEmpty()) return

        appendLine(".parallel(")
        parallelPart.forEachIndexed { threadIndex, threadPart ->
            if (threadPart.isEmpty()) return@forEachIndexed

            indentBlock {
                appendLine("thread(")

                indentBlock {
                    appendLinesJoining(threadPart.map { actorCallToString(it) }, ",\n")
                }

                val comaSuffix = if (threadIndex < parallelPart.lastIndex) "," else ""
                appendLine(")$comaSuffix")
            }
        }
        appendLine(")")
    }


    private fun TestCodeStringBuilder.appendSequentialPart(partName: String, actors: MutableList<Actor>) {
        if (actors.isEmpty()) return

        appendLine(".$partName(")
        indentBlock {
            appendLinesJoining(actors.map { actorCallToString(it) }, ",\n")
        }
        appendLine(")")
    }

    private fun actorCallToString(actor: Actor): String {
        val argumentsLine = argumentsToLine(actor.arguments)
        val methodNameString = "\"${actor.method.name}\""

        return "actor($methodNameString$argumentsLine)"
    }

    private fun argumentsToLine(arguments: List<Any?>): String {
        if (arguments.isEmpty()) return ""

        return arguments.joinToString(prefix = ", ", separator = ", ") { argumentInstantiationCode(it) }
    }

    private fun argumentInstantiationCode(argument: Any?): String {
        if (argument == null) return "null"

        return when (argument) {
            is Double, Int, Boolean -> "$argument"
            is Byte -> "(byte) $argument"
            is Short -> "(short) $argument"
            is Char -> "(char) ${argument.code}"
            is String -> "\"$argument\""
            is Float -> "${argument}F"
            is Long -> "${argument}L"
            // other types are not supported for now
            else -> argument.toString()
        }
    }

}

/**
 * Utility class to generate formatted code snippets
 */
private class TestCodeStringBuilder {

    private val stringBuilder = StringBuilder()

    private var indents: Int = 0

    fun appendLine(line: String) = stringBuilder.apply {
        append(currentIndent())
        appendLine(line)
    }


    fun appendLinesJoining(parts: List<String>, separator: String) = stringBuilder.apply {
        appendLine(parts.joinToString(separator) { currentIndent() + it })
    }

    fun indentBlock(blockIndents: Int = 1, action: () -> Unit) {
        indents += blockIndents
        action()
        indents -= blockIndents
    }

    private fun currentIndent() = TAB.repeat(indents)

    override fun toString() = stringBuilder.toString()

}

private const val TAB = "\t"

private val LINCHECK_PACKAGE_NAME = "org.jetbrains.kotlinx.lincheck"