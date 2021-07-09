/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*

actual fun printErr(message: String) {
    System.err.println(message)
}

internal actual fun StringBuilder.appendFailure(failure: LincheckFailure): StringBuilder {
    when (failure) {
        is IncorrectResultsFailure -> appendIncorrectResultsFailure(failure)
        is DeadlockWithDumpFailure -> appendDeadlockWithDumpFailure(failure)
        is UnexpectedExceptionFailure -> appendUnexpectedExceptionFailure(failure)
        is ValidationFailure -> appendValidationFailure(failure)
        is ObstructionFreedomViolationFailure -> appendObstructionFreedomViolationFailure(failure)
    }
    val results = if (failure is IncorrectResultsFailure) failure.results else null
    if (failure.trace != null) {
        appendLine()
        appendLine("= The following interleaving leads to the error =")
        appendTrace(failure.scenario, results, failure.trace)
        if (failure is DeadlockWithDumpFailure) {
            appendLine()
            append("All threads are in deadlock")
        }
    }
    return this
}

internal actual fun StringBuilder.appendDeadlockWithDumpFailure(failure: DeadlockWithDumpFailure): StringBuilder {
    appendLine("= The execution has hung, see the thread dump =")
    appendExecutionScenario(failure.scenario)
    appendLine()
    for ((t, stackTrace) in failure.threadDump.dump) {
        val threadNumber = if (t is FixedActiveThreadsExecutor.TestThread) t.iThread.toString() else "?"
        appendLine("Thread-$threadNumber:")
        stackTrace.map {
            StackTraceElement(it.className.removePrefix(TransformationClassLoader.REMAPPED_PACKAGE_CANONICAL_NAME), it.methodName, it.fileName, it.lineNumber)
        }.map { it.toString() }.filter { line ->
            "org.jetbrains.kotlinx.lincheck.strategy" !in line
                && "org.jetbrains.kotlinx.lincheck.runner" !in line
                && "org.jetbrains.kotlinx.lincheck.UtilsKt" !in line
        }.forEach { appendLine("\t$it") }
    }
    return this
}

internal actual fun StringBuilder.appendStateEquivalenceViolationMessage(sequentialSpecification: SequentialSpecification<*>) {
    append("To make verification faster, you can specify the state equivalence relation on your sequential specification.\n" +
            "At the current moment, `${sequentialSpecification.simpleName}` does not specify it, or the equivalence relation implementation is incorrect.\n" +
            "To fix this, please implement `equals()` and `hashCode()` functions on `${sequentialSpecification.simpleName}`; the simplest way is to extend `VerifierState`\n" +
            "and override the `extractState()` function, which is called at once and the result of which is used for further `equals()` and `hashCode()` invocations.")
}