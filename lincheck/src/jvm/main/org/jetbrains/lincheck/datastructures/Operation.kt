/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.datastructures

/**
 * Mark your method with this annotation to use it in concurrent testing as an operation.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
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
     * Operations of the same group are never executed concurrently.
     */
    val nonParallelGroup: String = "",

    /**
     * Specifies whether the operation can be cancelled if it suspends,
     * see `CancellableContinuation.cancel`; `true` by default.
     */
    val cancellableOnSuspension: Boolean = true,

    /**
     * Specifies whether this operation is blocking.
     * This way, if the test checks for a non-blocking progress guarantee,
     * **lincheck** will not fail the test if a hang is detected on
     * a running operation with this `blocking` marker.
     */
    val blocking: Boolean = false,

    /**
     * Specifies whether this cancellable operation supports
     * prompt cancellation, `false` by default. This parameter
     * is ignored if [.cancellableOnSuspension] is `false`.
     */
    val promptCancellation: Boolean = false,
)
