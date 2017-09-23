package com.devexperts.dxlab.lincheck.verifier;

/*
 * #%L
 * core
 * %%
 * Copyright (C) 2015 - 2017 Devexperts, LLC
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

import com.devexperts.dxlab.lincheck.Actor;
import com.devexperts.dxlab.lincheck.Result;
import com.devexperts.dxlab.lincheck.Utils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of this interface verifies that execution is correct with respect to the algorithm contract.
 * By default, {@link LinearizabilityVerifier} is used.
 * <p>
 * IMPORTANT!
 * All implementations should have the same constructor as {@link Verifier} has.
 */
public abstract class Verifier {
    protected final List<List<Actor>> actorsPerThread;
    protected final Object testInstance;
    protected final Method resetMethod;

    protected Verifier(List<List<Actor>> actorsPerThread, Object testInstance, Method resetMethod) {
        this.actorsPerThread = actorsPerThread;
        this.testInstance = testInstance;
        this.resetMethod = resetMethod;
    }

    /**
     * Verifies the specified results for correctness.
     * Throws {@link AssertionError} if results are not correct.
     */
    public abstract void verifyResults(List<List<Result>> results);

    /**
     * Executes list of actors sequentially (in one thread) and returns their results
     */
    protected List<Result> executeActors(Object testInstance, List<Actor> actors) {
        try {
            Utils.invokeReset(resetMethod, testInstance);
            return actors.stream().map(a -> executeActor(testInstance, a))
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Result executeActor(Object testInstance, Actor actor) {
        try {
            Object res = actor.method.invoke(testInstance, actor.arguments);
            if (actor.method.getReturnType() == void.class)
                return Result.createVoidResult();
            else
                return Result.createValueResult(res);
        } catch (InvocationTargetException invE) {
            Class<? extends Throwable> eClass = invE.getCause().getClass();
            for (Class<? extends Throwable> ec : actor.handledExceptions) {
                if (eClass.isAssignableFrom(ec))
                    return Result.createExceptionResult(eClass);
            }
            throw new IllegalStateException("Invalid exception as a result", invE);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke method " + actor.method, e);
        }
    }
}
