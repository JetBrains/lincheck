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

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import platform.posix.*
import kotlin.coroutines.*
import kotlin.native.concurrent.*
import kotlin.reflect.*

actual class TestClass(
    val create: () -> Any?
) {
    actual fun createInstance(): Any = create() ?: throw IllegalArgumentException("Constructor should not return null")
}

actual class SequentialSpecification<T> (val function: () -> Any?)

actual fun loadSequentialSpecification(sequentialSpecification: SequentialSpecification<*>): SequentialSpecification<out Any> = sequentialSpecification as SequentialSpecification<out Any>

actual fun <T : Any> SequentialSpecification<T>.getInitialState(): T = function() as T

internal actual fun createLincheckResult(res: Any?, wasSuspended: Boolean): Result {
    TODO("Not yet implemented")
}

internal actual fun executeValidationFunction(instance: Any, validationFunction: ValidationFunction): Throwable? {
    try {
        validationFunction.function(instance)
    } catch (e: Throwable) {
        return e
    }
    return null
}

/**
 * loader class type should be ClassLoader in jvm
 */
internal actual fun ExecutionScenario.convertForLoader(loader: Any): ExecutionScenario {
    return this
}

actual fun chooseSequentialSpecification(sequentialSpecificationByUser: SequentialSpecification<*>?, testClass: TestClass): SequentialSpecification<*> =
    sequentialSpecificationByUser ?: SequentialSpecification<Any>(testClass.create)

actual fun storeCancellableContinuation(cont: CancellableContinuation<*>) {
    TODO("Not yet implemented")
}

internal actual fun executeActor(
    instance: Any,
    actor: Actor,
    completion: Continuation<Any?>?
): Result {
    return try {
        val r = actor.function(instance, actor.arguments)
        ValueResult(r)
    } catch (e: Throwable) {
        if (actor.handledExceptions.any { it.safeCast(e) != null }) { // Do a cast. If == null, cast failed and e is not an instance of it
            ExceptionResult(e::class, false)
        } else {
            throw e
        }
    }
}

/**
 * Collects the current thread dump and keeps only those
 * threads that are related to the specified [runner].
 */
internal actual fun collectThreadDump(runner: Runner): ThreadDump {
    return ThreadDump() // No thread dumps in kotlin native
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
        appendLine("Sorry, there are no trace in kotlin native :(")
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
    appendLine("Sorry, there are no threadDumps in kotlin native :(")
    return this
}

val STDERR = platform.posix.fdopen(2, "w")
actual fun printErr(message: String) {
    fprintf(STDERR, message + "\n")
    fflush(STDERR)
}

internal actual fun nativeFreeze(any: Any): Unit {
    any.freeze()
}
inline fun <reified T> LincheckAtomicArray<T>.toArray(): Array<T> = Array(this.array.size) { this.array[it].value!! }
fun <T> Array<T>.toLincheckAtomicArray(): LincheckAtomicArray<T> {
    val ans = LincheckAtomicArray<T>(this.size)
    for (i in this.indices) {
        ans.array[i].value = this[i]
    }
    return ans
}

internal actual fun StringBuilder.appendStateEquivalenceViolationMessage(sequentialSpecification: SequentialSpecification<*>) {
    append("To make verification faster, you can specify the state equivalence relation on your sequential specification.\n" +
            "At the current moment, `${sequentialSpecification.function}` does not specify it, or the equivalence relation implementation is incorrect.\n" +
            "To fix this, please implement `equals()` and `hashCode()` functions on `${sequentialSpecification.function}`; the simplest way is to extend `VerifierState`\n" +
            "and override the `extractState()` function, which is called at once and the result of which is used for further `equals()` and `hashCode()` invocations.")
}
