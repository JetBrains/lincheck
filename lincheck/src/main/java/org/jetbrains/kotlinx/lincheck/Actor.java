package org.jetbrains.kotlinx.lincheck;

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

import org.jetbrains.kotlinx.lincheck.annotations.Operation;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The actor entity describe the operation with its parameters
 * which is executed during the testing.
 *
 * @see Operation
 */
public class Actor {
    public final Method method;
    public final Object[] arguments;
    public final List<Class<? extends Throwable>> handledExceptions;

    public Actor(Method method, List<Object> arguments, List<Class<? extends Throwable>> handledExceptions) {
        this(method, arguments.toArray(), handledExceptions);
    }

    public Actor(Method method, Object[] arguments, List<Class<? extends Throwable>> handledExceptions) {
        this.method = method;
        this.arguments = arguments;
        this.handledExceptions = handledExceptions;
    }

    @Override
    public String toString() {
        return method.getName() + "(" + Stream.of(arguments).map(Object::toString).collect(Collectors.joining(", ")) + ")";
    }

    public boolean handlesExceptions() {
        return !handledExceptions.isEmpty();
    }
}