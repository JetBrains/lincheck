package org.jetbrains.kotlinx.lincheck.annotations;

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

import kotlinx.coroutines.*;
import java.lang.annotation.*;

/**
 * Mark your method with this annotation in order
 * to use it in concurrent testing as an operation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Operation {
    /**
     * Binds the arguments of this operation with the specified {@link Param parameter configurations}
     * by their {@link Param#name()} names.
     */
    String[] params() default {};

    /**
     * Set it to {@code true} if you this operation should be called
     * at most once during the test invocation; {@code false} by default.
     */
    boolean runOnce() default false;

    /**
     * Specifies the operation group which can add some execution restriction.
     * @see OpGroupConfig#name()
     *
     * @deprecated use {@link Operation#nonParallelGroup()} instead.
     */
    @SuppressWarnings("removal")
    @Deprecated(forRemoval = true)
    String group() default "";

    /**
     * Operations of the same group are never executed concurrently.
     */
    String nonParallelGroup() default "";

    /**
     * Handle the specified exceptions as a result of this operation invocation.
     */
    Class<? extends Throwable>[] handleExceptionsAsResult() default {};

    /**
     * Specifies whether the operation can be cancelled if it suspends,
     * see {@link CancellableContinuation#cancel}; {@code true} by default.
     */
    boolean cancellableOnSuspension() default true;

    /**
     * The operation marked with [allowExtraSuspension] is allowed to
     * suspend (and, therefore, be cancelled if [cancellableOnSuspension]
     * is set to `true`) even if it should not according to the sequential
     * specification. The one may consider this as a relaxation of the
     * dual data structures formalism.
     */
    boolean allowExtraSuspension() default false;

    /**
     * Specifies whether this operation is blocking.
     * This way, if the test checks for a non-blocking progress guarantee,
     * <b>lincheck</b> will not fail the test if a hang is detected on
     * a running operations with this {@code blocking} marker.
     */
    boolean blocking() default false;

    /**
     * Specifies whether this operation invocation can lead
     * to a blocking behavior of another concurrent operation.
     * This way, if the test checks for a non-blocking progress guarantee,
     * <b>lincheck</b> will not fail the test if a hang is detected
     * while one of the operations marked with {@link #causesBlocking}
     * is running concurrently. Note, that this operation is not
     * considered as blocking until it is marked as {@link #blocking}.
     */
    boolean causesBlocking() default false;

    /**
     * Specifies whether this cancellable operation supports
     * prompt cancellation, {@code false} by default. This parameter
     * is ignored if {@link #cancellableOnSuspension} is {@code false}.
     */
    boolean promptCancellation() default false;
}
