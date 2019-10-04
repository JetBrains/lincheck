package org.jetbrains.kotlinx.lincheck.runner;

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

import org.jetbrains.kotlinx.lincheck.Actor;
import org.jetbrains.kotlinx.lincheck.ExecutionClassLoader;
import org.jetbrains.kotlinx.lincheck.TestReport;
import org.jetbrains.kotlinx.lincheck.TransformationClassLoader;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario;
import org.jetbrains.kotlinx.lincheck.strategy.Strategy;
import org.jetbrains.kotlinx.lincheck.util.Either;
import org.objectweb.asm.ClassVisitor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runner determines how to run your concurrent test. In order to support techniques
 * like fibers, it may require code transformation, so {@link #needsTransformation()}
 * method has to return {@code true} and {@link #createTransformer(ClassVisitor)}
 * one has to be implemented.
 */
public abstract class Runner {
    protected final ExecutionScenario scenario;
    protected final Class<?> testClass;
    public final ExecutionClassLoader classLoader;
    protected final AtomicInteger completedOrSuspendedThreads = new AtomicInteger(0);
    protected final Strategy strategy;

    protected Runner(ExecutionScenario scenario, Strategy strategy, Class<?> testClass) {
        this.scenario = scenario;
        classLoader = (this.needsTransformation() || strategy.needsTransformation()) ?
            new TransformationClassLoader(strategy, this) : new ExecutionClassLoader();
        this.testClass = loadClass(testClass.getTypeName());
        this.strategy = strategy;
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
     * Runs next invocation
     * @return the obtained results
     */
    public abstract Either<TestReport, ExecutionResult> run() throws InterruptedException;

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
    public void onException(int iThread, Throwable e) {}

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended
     * @param iThread number of invoking thread
     */
    public void afterCoroutineSuspended(int iThread) {
        throw new UnsupportedOperationException("Coroutines are not supported");
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed
     * @param iThread number of invoking thread
     */
    public void beforeCoroutineResumed(int iThread) {
        throw new UnsupportedOperationException("Coroutines are not supported");
    }

    /**
     * This method is invoked by a test thread
     * if the current coroutine can be resumed
     * @param iThread number of invoking thread
     * @param iActor number of actor invoked
     */
    public boolean canResumeCoroutine(int iThread, int iActor) {
        throw new UnsupportedOperationException("Coroutines are not supported");
    }

    /**
     * Closes used for this runner resources.
     */
    public void close() {}

    /**
     * @return whether all scenario threads are completed or suspended
     */
    public boolean isParallelExecutionCompleted() {
        return completedOrSuspendedThreads.get() == scenario.getThreads();
    }
}
