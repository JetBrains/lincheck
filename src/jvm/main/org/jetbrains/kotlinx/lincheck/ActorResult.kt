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
 * The instances of this interface represent a result of actor invocation.
 */
sealed interface ActorResult

/**
 * Type of result used if the actor invocation returns any value.
 */
data class ValueActorResult(val value: Any?) : ActorResult {
    override fun toString() = "$value"
}

/**
 * Type of result used if the actor invocation does not return value.
 */
object VoidActorResult : ActorResult {
    override fun toString() = VOID
}

private const val VOID = "void"

object CancelledActorResult : ActorResult {
    override fun toString() = "CANCELLED"
}

/**
 * Type of result used if the actor invocation fails with the specified in {@link Operation#handleExceptionsAsResult()} exception [tClazzFullName].
 */
class ExceptionActorResult private constructor(
    /**
     * Exception is stored to print it's stackTrace in case of incorrect results
     */
    val throwable: Throwable
) : ActorResult {

    internal val tClassCanonicalName: String get() = throwable::class.java.canonicalName

    override fun toString(): String = throwable::class.java.simpleName
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExceptionActorResult) return false

        if (tClassCanonicalName != other.tClassCanonicalName) return false
        return true
    }

    override fun hashCode(): Int {
        return tClassCanonicalName.hashCode()
    }


    companion object {
        fun create(throwable: Throwable) = ExceptionActorResult(throwable)
    }
}

// for byte-code generation
@JvmSynthetic
fun createExceptionResult(throwable: Throwable) = ExceptionActorResult.create(throwable)

/**
 * Type of result used if the actor invocation suspended the thread and did not get the final result yet
 * though it can be resumed later
 */
object NoActorResult : ActorResult {
    override fun toString() = "-"
}

object SuspendedActorResult : ActorResult {
    override fun toString() = "Suspended"
}

/**
 * Type of result used for verification.
 * Resuming thread writes result of the suspension point and continuation to be executed in the resumed thread into [contWithSuspensionPointRes].
 */
internal data class ResumedActorResult(val contWithSuspensionPointRes: Pair<Continuation<Any?>?, Result<Any?>>) : ActorResult {
    lateinit var resumedActor: Actor
    lateinit var by: Actor
}