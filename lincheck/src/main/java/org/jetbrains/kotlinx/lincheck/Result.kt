package org.jetbrains.kotlinx.lincheck

import kotlin.coroutines.Continuation

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

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
    protected val wasSuspendedPrefix: String get() = (if (wasSuspended) "S + " else "")
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

/**
 * Type of result used if the actor invocation fails with the specified in {@link Operation#handleExceptionsAsResult()} exception [tClazz].
 */
data class ExceptionResult @JvmOverloads constructor(val tClazz: Class<out Throwable>?, override val wasSuspended: Boolean = false) : Result() {
    override fun toString() = wasSuspendedPrefix + "${tClazz?.simpleName}"

    /**
     * Check equals carefully because stored exceptions may be loaded by different loaders
     */
    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other !is ExceptionResult) return false
        if (tClazz == other.tClazz) return true
        return tClazz?.name?.equals(other.tClazz?.name) ?: false
    }

    override fun hashCode(): Int = tClazz?.name.hashCode()
}

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