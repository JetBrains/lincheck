/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * Copyright (C) 2019-2020 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.runner;

import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.annotations.*;
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.strategy.*;
import org.objectweb.asm.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.jetbrains.kotlinx.lincheck.UtilsKt.convertForLoader;

/**
 * Runner determines how to run your concurrent test. In order to support techniques
 * like fibers, it may require code transformation, so {@link #needsTransformation()}
 * method has to return {@code true} and {@link #createTransformer(ClassVisitor)}
 * one has to be implemented.
 */
public abstract class Runner {
    protected ExecutionScenario scenario;
    protected Class<?> testClass;
    protected final List<Method> validationFunctions;
    public final Method stateRepresentationFunction;
    protected final Strategy strategy;
    public ExecutionClassLoader classLoader;

    protected final AtomicInteger completedOrSuspendedThreads = new AtomicInteger(0);

    protected Runner(Strategy strategy, Class<?> testClass, List<Method> validationFunctions, Method stateRepresentationFunction) {
        this.testClass = testClass;
        this.strategy = strategy;
        this.scenario = strategy.getScenario();
        this.validationFunctions = validationFunctions;
        this.stateRepresentationFunction = stateRepresentationFunction;
        this.classLoader = (this.needsTransformation() || strategy.needsTransformation()) ?
                new TransformationClassLoader(strategy, this) : new ExecutionClassLoader();    }

    /**
     * This method is a part of Runner initialization.
     * It is separated from constructor to allow certain strategy initialization steps in between.
     * That may be needed, for example, for transformation logic and `ManagedStateHolder` initialization.
     */
    public void initialize() {
        this.scenario = convertForLoader(strategy.getScenario(), classLoader);
        this.testClass = loadClass(testClass.getTypeName());
    }

    /**
     * Returns the current state representation of the test instance constructed via
     * the function marked with {@link StateRepresentation} annotation, or {@code null}
     * if no such function is provided.
     *
     * Please not, that it is unsafe to call this method concurrently with the running scenario.
     * However, it is fine to call it if the execution is paused somewhere in the middle.
     */
    public String constructStateRepresentation() {
        return null;
    }

    /**
     * Loads class using runner's class loader
     */
    private Class<?> loadClass(String className) {
        try {
            return classLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load class " + className, e);
        }
    }

    /**
     * Creates required for this runner transformer.
     * Throws {@link UnsupportedOperationException} by default.
     *
     * @return class visitor which transform the code due to support this runner.
     */
    public ClassVisitor createTransformer(ClassVisitor cv) {
        throw new UnsupportedOperationException(getClass() + " runner does not transform classes");
    }

    /**
     * This method has to return {@code true} if code transformation is required for runner.
     * Returns {@code false} by default.
     */
    public boolean needsTransformation() {
        return false;
    }

    /**
     * Runs the next invocation.
     */
    public abstract InvocationResult run();

    /**
     * This method is invoked by every test thread as the first operation.
     * @param iThread number of invoking thread
     */
    public void onStart(int iThread) {}

    /**
     * This method is invoked by every test thread as the last operation
     * if no exception has been thrown.
     * @param iThread number of invoking thread
     */
    public void onFinish(int iThread) {}

    /**
     * This method is invoked by a test thread
     * if an exception has been thrown.
     * @param iThread number of invoking thread
     */
    public void onFailure(int iThread, Throwable e) {}

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended
     * @param iThread number of invoking thread
     */
    void afterCoroutineSuspended(int iThread) {
        throw new UnsupportedOperationException("Coroutines are not supported");
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed
     * @param iThread number of invoking thread
     */
    void afterCoroutineResumed(int iThread) {
        throw new UnsupportedOperationException("Coroutines are not supported");
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was cancelled
     * @param iThread number of invoking thread
     */
    void afterCoroutineCancelled(int iThread) {
        throw new UnsupportedOperationException("Coroutines are not supported");
    }

    /**
     * Returns `true` if the coroutine corresponding to
     * the actor `iActor` in the thread `iThread` is resumed.
     */
    public boolean isCoroutineResumed(int iThread, int iActor) {
        throw new UnsupportedOperationException("Coroutines are not supported");
    }

    /**
     * Is invoked before each actor execution in a thread.
     */
    @SuppressWarnings("unused") // used by generated code
    public void onActorStart(int iThread) {
        strategy.onActorStart(iThread);
    }

    /**
     * Closes used for this runner resources.
     */
    public void close() {}

    /**
     * @return whether all scenario threads are completed or suspended
     */
    @SuppressWarnings("unused") // used by generated code
    public boolean isParallelExecutionCompleted() {
        return completedOrSuspendedThreads.get() == scenario.getThreads();
    }
}
