/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.lincheck.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED


/**
 * Instances of this interface represent a result of an actor invocation.
 */
sealed interface LincheckResult

/**
 * Represents the result of an actor invocation that has not finished yet.
 */
val NoResult : LincheckResult? = null

/**
 * Represents the result of an actor invocation that was completed normally and does not return any value.
 */
data object VoidResult : LincheckResult {
    override fun toString() = "void"
}

/**
 * Represents the result of an actor invocation that was completed normally and returned [value].
 */
data class ValueResult(val value: Any?) : LincheckResult {
    override fun toString() = value.toString()
}

/**
 * Represents the result of an actor invocation that failed with an exception.
 */
data class ExceptionResult(
    /**
     * Exception is stored to print it's stackTrace in case of incorrect results
     */
    val throwable: Throwable
) : LincheckResult {

    internal val throwableCanonicalName: String get() =
        throwable::class.java.canonicalName

    override fun toString(): String =
        throwable::class.java.simpleName

    override fun hashCode(): Int =
        throwableCanonicalName.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExceptionResult) return false
        return throwableCanonicalName == other.throwableCanonicalName
    }
}

/**
 * Represents the result of an actor invocation that was suspended during its execution.
 */
data object SuspendedResult : LincheckResult {
    override fun toString() = "SUSPENDED"
}

/**
 * Represents the result of an actor invocation that was cancelled during its execution.
 */
data object CancelledResult : LincheckResult {
    override fun toString() = "CANCELLED"
}

/**
 * Type of result used for verification.
 * Resuming thread writes result of the suspension point and continuation to be executed in the resumed thread into [contWithSuspensionPointRes].
 */
internal data class ResumedResult(val contWithSuspensionPointRes: Pair<Continuation<Any?>?, Result<Any?>>) : LincheckResult {
    lateinit var resumedActor: Actor
    lateinit var by: Actor
}

/**
 * Creates [LincheckResult] of corresponding type from any given value.
 *
 *   - Java [Void] and Kotlin [Unit] classes are represented as [VoidResult].
 *
 *   - Success values of [Result] instances are represented as either [VoidResult] or [ValueResult].
 *
 *   - Failure values of [Result] instances are represented as [ExceptionResult].
 *
 *   - Instances of [Throwable] are represented as [ExceptionResult].
 *
 *   - The special [COROUTINE_SUSPENDED] value is represented as [SuspendedResult]
 *     (this value is returned when the coroutine suspends).
 *
 *   - Any other value is represented as [ValueResult].
 */
internal fun createLincheckResult(result: Any?): LincheckResult = when {
    result is Unit      -> VoidResult
    result.isJavaVoid() -> VoidResult

    result is Result<Any?> -> result.toLincheckResult()
    result is Throwable    -> ExceptionResult(result)

    result === COROUTINE_SUSPENDED -> SuspendedResult

    else -> ValueResult(result)
}

private fun Any?.isJavaVoid(): Boolean =
    this != null && this.javaClass.isAssignableFrom(Void.TYPE)

internal fun Result<Any?>.toLincheckResult() = when {
    isSuccess -> when (val value = getOrNull()) {
        is Unit -> VoidResult
        else    -> ValueResult(value)
    }
    isFailure -> ExceptionResult(exceptionOrNull()!!)
    else      -> unreachable()
}

/* ==== Methods used in byte-code generation ==== */

@JvmSynthetic
internal fun createVoidLincheckResult() = VoidResult

@JvmSynthetic
internal fun createValueLincheckResult(value: Any?) = ValueResult(value)

@JvmSynthetic
internal fun createExceptionLincheckResult(throwable: Throwable) = ExceptionResult(throwable)