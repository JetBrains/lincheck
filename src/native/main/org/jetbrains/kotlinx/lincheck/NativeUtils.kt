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
import kotlin.coroutines.*

actual class TestClass(
    actual val name: String,
    val function: () -> Any
) {
    actual fun createInstance(): Any = function()
}

actual class SequentialSpecification<T> (val function: () -> Any)

actual fun loadSequentialSpecification(sequentialSpecification: SequentialSpecification<*>): SequentialSpecification<out Any> = sequentialSpecification as SequentialSpecification<out Any>

actual fun <T : Any> SequentialSpecification<T>.getInitialState(): T = function() as T

internal actual fun createLincheckResult(res: Any?, wasSuspended: Boolean): Result {
    TODO("Not yet implemented")
}

internal actual fun executeValidationFunction(instance: Any, validationFunction: ValidationFunction): Throwable? {
    TODO("Not yet implemented")
}

/**
 * loader class type should be ClassLoader in jvm
 */
internal actual fun ExecutionScenario.convertForLoader(loader: Any): ExecutionScenario {
    return this
}

actual fun chooseSequentialSpecification(sequentialSpecificationByUser: SequentialSpecification<*>?, testClass: TestClass): SequentialSpecification<*> =
    sequentialSpecificationByUser ?: SequentialSpecification<Any>(testClass.function)

actual fun storeCancellableContinuation(cont: CancellableContinuation<*>) {
    TODO("Not yet implemented")
}

internal actual fun executeActor(
    instance: Any,
    actor: Actor,
    completion: Continuation<Any?>?
): Result {
    TODO("Not yet implemented")
}

/**
 * Collects the current thread dump and keeps only those
 * threads that are related to the specified [runner].
 */
internal actual fun collectThreadDump(runner: Runner): ThreadDump {
    TODO("Not yet implemented")
}