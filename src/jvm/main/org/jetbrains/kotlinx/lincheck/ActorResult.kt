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
 * Instances of this interface represent a result of actor invocation.
 */
sealed interface ActorResult

/**
 * Actor result used if the actor invocation completes normally.
 */
data class ValueActorResult(val value: Any?) : ActorResult {
    override fun toString() = if (value === Unit) "void" else "$value"
}

/**
 * Actor result used if the actor invocation completes normally and does not return value.
 */
val VoidActorResult = ValueActorResult(Unit)

/**
 * Actor result used if the actor invocation fails with an exception.
 */
class ExceptionActorResult(val throwable: Throwable) : ActorResult {

    internal val throwableCanonicalName: String get() =
        throwable::class.java.canonicalName

    override fun toString(): String = throwable::class.java.simpleName
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExceptionActorResult) return false
        return throwableCanonicalName == other.throwableCanonicalName
    }

    override fun hashCode(): Int {
        return throwableCanonicalName.hashCode()
    }
}

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

object CancelledActorResult : ActorResult {
    override fun toString() = "CANCELLED"
}

/**
 * Type of result used for verification.
 * Resuming thread writes result of the suspension point and continuation to be executed in the resumed thread into [contWithSuspensionPointRes].
 */
internal data class ResumedActorResult(val contWithSuspensionPointRes: Pair<Continuation<Any?>?, Result<Any?>>) : ActorResult {
    lateinit var resumedActor: Actor
    lateinit var by: Actor
}


/* ==== Methods used in byte-code generation ==== */

@JvmSynthetic
internal fun createVoidActorResult() = VoidActorResult

@JvmSynthetic
internal fun createExceptionResult(throwable: Throwable) = ExceptionActorResult(throwable)