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
sealed class Result {
    abstract val wasSuspended: Boolean
    protected val wasSuspendedPrefix: String get() = (if (wasSuspended) "SUSPENDED + " else "")
}

/**
 * Type of result used if the actor invocation returns any value.
 */
data class ValueResult @JvmOverloads constructor(val value: Any?, override val wasSuspended: Boolean = false) : Result() {
    override fun toString() = wasSuspendedPrefix + "$value"
}

/**
 * Type of result used if the actor invocation does not return value.
 */
object VoidResult : Result() {
    override val wasSuspended get() = false
    override fun toString() = wasSuspendedPrefix + VOID
}

object SuspendedVoidResult : Result() {
    override val wasSuspended get() = true
    override fun toString() = wasSuspendedPrefix + VOID
}

private const val VOID = "void"

object Cancelled : Result() {
    override val wasSuspended get() = true
    override fun toString() = wasSuspendedPrefix + "CANCELLED"
}

/**
 * Type of result used if the actor invocation fails with the specified in {@link Operation#handleExceptionsAsResult()} exception [tClazz].
 */
@Suppress("DataClassPrivateConstructor")
data class ExceptionResult private constructor(val tClazz: Class<out Throwable>, override val wasSuspended: Boolean) : Result() {
    override fun toString() = wasSuspendedPrefix + tClazz.simpleName

    companion object {
        @Suppress("UNCHECKED_CAST")
        @JvmOverloads
        fun create(tClazz: Class<out Throwable>, wasSuspended: Boolean = false) = ExceptionResult(tClazz.normalize(), wasSuspended)
    }
}
// for byte-code generation
@JvmSynthetic
fun createExceptionResult(tClazz: Class<out Throwable>) = ExceptionResult.create(tClazz, false)

/**
 * Type of result used if the actor invocation suspended the thread and did not get the final result yet
 * though it can be resumed later
 */
object NoResult : Result() {
    override val wasSuspended get() = false
    override fun toString() = "-"
}

object Suspended : Result() {
    override val wasSuspended get() = true
    override fun toString() = "S"
}

/**
 * Type of result used for verification.
 * Resuming thread writes result of the suspension point and continuation to be executed in the resumed thread into [contWithSuspensionPointRes].
 */
internal data class ResumedResult(val contWithSuspensionPointRes: Pair<Continuation<Any?>?, kotlin.Result<Any?>>) : Result() {
    override val wasSuspended: Boolean get() = true

    lateinit var resumedActor: Actor
    lateinit var by: Actor
}