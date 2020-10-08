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
     */
    String group() default "";

    /**
     * Handle the specified exceptions as a result of this operation invocation.
     */
    Class<? extends Throwable>[] handleExceptionsAsResult() default {};

    /**
     * Specified whether the operation can be cancelled if it suspends,
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
}
