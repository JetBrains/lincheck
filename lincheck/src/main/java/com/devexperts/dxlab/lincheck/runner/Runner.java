package com.devexperts.dxlab.lincheck.runner;

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

import com.devexperts.dxlab.lincheck.ExecutionClassLoader;
import com.devexperts.dxlab.lincheck.TransformationClassLoader;
import com.devexperts.dxlab.lincheck.execution.ExecutionResult;
import com.devexperts.dxlab.lincheck.execution.ExecutionScenario;
import com.devexperts.dxlab.lincheck.strategy.Strategy;
import com.devexperts.jagent.ClassInfo;
import org.objectweb.asm.ClassVisitor;

/**
 * Runner determines how to run your concurrent test. In order to support techniques
 * like fibers, it may require code transformation, so {@link #needsTransformation()}
 * method has to return {@code true} and {@link #createTransformer(ClassVisitor, ClassInfo)}
 * one has to be implemented.
 */
public abstract class Runner {
    protected final ExecutionScenario scenario;
    protected final Class<?> testClass;
    public final ExecutionClassLoader classLoader;

    protected Runner(ExecutionScenario scenario, Strategy strategy, Class<?> testClass) {
        this.scenario = scenario;
        classLoader = (this.needsTransformation() || strategy.needsTransformation()) ?
            new TransformationClassLoader(strategy, this) : new ExecutionClassLoader();
        this.testClass = loadClass(testClass.getTypeName());
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
    public ClassVisitor createTransformer(ClassVisitor cv, ClassInfo classInfo) {
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
    public abstract ExecutionResult run() throws InterruptedException;

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
     * Closes used for this runner resources.
     */
    public void close() {}
}
