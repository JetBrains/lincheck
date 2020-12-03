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
package org.jetbrains.kotlinx.lincheck.annotations

import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import kotlin.reflect.KClass

/**
 * Mark your method with this annotation in order
 * to use it in concurrent testing as an operation.
 */
@Retention(RetentionPolicy.RUNTIME)
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
         * Specifies the operation group which can add some execution restriction.
         * @see OpGroupConfig.name
         */
        val group: String = "",
        /**
         * Handle the specified exceptions as a result of this operation invocation.
         */
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
         */
        val allowExtraSuspension: Boolean = false,
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
        val promptCancellation: Boolean = false)