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

import kotlin.coroutines.*


/**
 * The instance of this class represents a result of actor invocation.
 *
 * <p> If the actor invocation suspended the thread and did not get the final result yet
 * though it can be resumed later, then the {@link Type#NO_RESULT no_result result type} is used.
 *
 * [wasSuspended] is true if before getting this result the actor invocation suspended the thread.
 * If result is [NoResult] and [wasSuspended] is true it means that
 * the execution thread was suspended without any chance to be resumed,
 * meaning that all other execution threads completed their execution or were suspended too.
 */
sealed interface Result

/**
 * Type of result used if the actor invocation returns any value.
 */
data class ValueResult(val value: Any?) : Result {
    override fun toString() = "$value"
}

/**
 * Type of result used if the actor invocation does not return value.
 */
object VoidResult : Result {
    override fun toString() = VOID
}

private const val VOID = "void"

object Cancelled : Result {
    override fun toString() = "CANCELLED"
}

/**
 * Type of result used if the actor invocation fails with the specified in {@link Operation#handleExceptionsAsResult()} exception [tClazzFullName].
 */
class ExceptionResult(
    /**
     * Exception is stored to print it's stackTrace in case of incorrect results
     */
    val throwable: Throwable
) : Result {

    internal val tClassCanonicalName: String get() = throwable::class.java.canonicalName

    override fun toString(): String = throwable::class.java.simpleName
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExceptionResult) return false

        if (tClassCanonicalName != other.tClassCanonicalName) return false
        return true
    }

    override fun hashCode(): Int {
        return tClassCanonicalName.hashCode()
    }


    companion object {
        fun create(throwable: Throwable) = ExceptionResult(throwable)
    }
}

// for byte-code generation
@JvmSynthetic
fun createExceptionResult(throwable: Throwable) = ExceptionResult.create(throwable)

/**
 * Type of result used if the actor invocation suspended the thread and did not get the final result yet
 * though it can be resumed later
 */
object NoResult : Result {
    override fun toString() = "-"
}

object Suspended : Result {
    override fun toString() = "Suspended"
}

/**
 * Type of result used for verification.
 * Resuming thread writes result of the suspension point and continuation to be executed in the resumed thread into [contWithSuspensionPointRes].
 */
internal data class ResumedResult(val contWithSuspensionPointRes: Pair<Continuation<Any?>?, kotlin.Result<Any?>>) : Result {
    lateinit var resumedActor: Actor
    lateinit var by: Actor
}