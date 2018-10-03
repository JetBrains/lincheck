package com.devexperts.dxlab.lincheck;

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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

public class Utils {
    private Utils() {}

    private static volatile int consumedCPU = (int) System.currentTimeMillis();

    /**
     * Busy wait, used by stress strategy.
     */
    public static void consumeCPU(int tokens) {
        int t = consumedCPU; // volatile read
        for (int i = tokens; i > 0; i--)
            t += (t * 0x5DEECE66DL + 0xBL + i) & (0xFFFFFFFFFFFFL);
        if (t == 42)
            consumedCPU += t;
    }

    /**
     * Creates test class instance using empty arguments constructor
     */
    public static Object createTestInstance(Class<?> testClass) {
        try {
            return testClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Test class should have empty public constructor", e);
        }
    }

    /**
     * Executes list of actors on the test instance sequentially (in one thread)
     * and returns their results.
     */
    public static List<Result> executeActors(Object testInstance, List<Actor> actors) {
        try {
            return actors.stream().map(a -> executeActor(testInstance, a))
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Executes the specified actor on the test instance and returns its result.
     */
    public static Result executeActor(Object testInstance, Actor actor) {
        try {
            Method m = testInstance.getClass().getMethod(actor.method.getName(), actor.method.getParameterTypes());
            Object res = m.invoke(testInstance, actor.arguments);
            if (actor.method.getReturnType() == void.class)
                return Result.createVoidResult();
            else
                return Result.createValueResult(res);
        } catch (InvocationTargetException invE) {
            Class<? extends Throwable> eClass = invE.getCause().getClass();
            for (Class<? extends Throwable> ec : actor.handledExceptions) {
                if (ec.isAssignableFrom(eClass))
                    return Result.createExceptionResult(eClass);
            }
            throw new IllegalStateException("Invalid exception as a result", invE);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke method " + actor.method, e);
        }
    }
}
