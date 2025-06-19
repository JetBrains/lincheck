/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.annotations

import kotlin.reflect.KClass

/**
 * Mark your method with this annotation to use it in concurrent testing as an operation.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Deprecated(
    level = DeprecationLevel.WARNING,
    message = "Use org.jetbrains.lincheck.datastructures.Operation instead.",
)
annotation class Operation(
    /**
     * Binds the arguments of this operation with the specified [parameter configurations][Param]
     * by their [Param.name] names.
     */
    val params: Array<String> = [],

    /**
     * Set it to `true` if you this operation should be called
     * at most once during the test invocation; `false` by default.
     */
    val runOnce: Boolean = false,

    /**
     * Specifies the operation group which can add some execution restriction.
     * @see OpGroupConfig.name
     */
    @Deprecated("use {@link Operation#nonParallelGroup()} instead.")
    val group: String = "",

    /**
     * Operations of the same group are never executed concurrently.
     */
    val nonParallelGroup: String = "",

    /**
     * Handle the specified exceptions as a result of this operation invocation.
     *
     */
    @Deprecated("all exceptions now handled as possible results")
    val handleExceptionsAsResult: Array<KClass<out Throwable>> = [],

    /**
     * Specifies whether the operation can be cancelled if it suspends,
     * see [CancellableContinuation.cancel]; `true` by default.
     */
    val cancellableOnSuspension: Boolean = true,
    /**
     * The operation marked with [allowExtraSuspension] is allowed to
     * suspend (and, therefore, be cancelled if [cancellableOnSuspension]
     * is set to `true`) even if it should not according to the sequential
     * specification. The one may consider this as a relaxation of the
     * dual data structures formalism.
     *
     */
    @Deprecated("extra suspensions are now allowed for all operations")
    val allowExtraSuspension: Boolean = true,

    /**
     * Specifies whether this operation is blocking.
     * This way, if the test checks for a non-blocking progress guarantee,
     * **lincheck** will not fail the test if a hang is detected on
     * a running operations with this `blocking` marker.
     */
    val blocking: Boolean = false,

    /**
     * Specifies whether this operation invocation can lead
     * to a blocking behavior of another concurrent operation.
     * This way, if the test checks for a non-blocking progress guarantee,
     * **lincheck** will not fail the test if a hang is detected
     * while one of the operations marked with [.causesBlocking]
     * is running concurrently. Note, that this operation is not
     * considered as blocking until it is marked as [.blocking].
     */
    val causesBlocking: Boolean = false,

    /**
     * Specifies whether this cancellable operation supports
     * prompt cancellation, `false` by default. This parameter
     * is ignored if [.cancellableOnSuspension] is `false`.
     */
    val promptCancellation: Boolean = false,
)
