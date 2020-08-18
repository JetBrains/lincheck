package org.jetbrains.kotlinx.lincheck.strategy.managed;

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

import kotlin.jvm.functions.Function0;
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.runner.*;
import org.jetbrains.kotlinx.lincheck.strategy.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.Remapper;

import java.lang.reflect.*;
import java.util.*;

/**
 * This is an abstract class for all managed strategies.
 * This abstraction helps to choose a proper {@link Runner},
 * to transform byte-code in order to insert required for managing instructions,
 * and to hide class loading problems from the strategy algorithm.
 */
public abstract class ManagedStrategy extends Strategy {
    /**
     * Number of threads
     */
    protected final int nThreads;

    protected final Runner runner;
    private final List<ManagedStrategyGuarantee> guarantees;
    private final boolean shouldMakeStateRepresentation;
    private final boolean eliminateLocalObjects;
    protected boolean loggingEnabled = false;
    private final List<Function0<CodeLocation>> codeLocationConstructors = new ArrayList<>(); // for trace construction
    private final List<CodeLocation> codeLocations = new ArrayList<>();

    protected ManagedStrategy(Class<?> testClass, ExecutionScenario scenario, List<Method> validationFunctions,
                              Method stateRepresentation, List<ManagedStrategyGuarantee> guarantees, long timeoutMs, boolean eliminateLocalObjects) {
        super(scenario);
        nThreads = scenario.parallelExecution.size();
        this.guarantees = guarantees;
        this.shouldMakeStateRepresentation = stateRepresentation != null;
        this.eliminateLocalObjects = eliminateLocalObjects;
        runner = new ParallelThreadsRunner(this, testClass, validationFunctions, stateRepresentation, true, timeoutMs) {
            @Override
            public void onStart(int threadId) {
                super.onStart(threadId);
                ManagedStrategy.this.onStart(threadId);
            }

            @Override
            public void onFinish(int threadId) {
                ManagedStrategy.this.onFinish(threadId);
                super.onFinish(threadId);
            }

            @Override
            public void onFailure(int threadId, Throwable e) {
                ManagedStrategy.this.onFailure(threadId, e);
                super.onFailure(threadId, e);
            }

            @Override
            public void afterCoroutineSuspended(int threadId) {
                super.afterCoroutineSuspended(threadId);
                ManagedStrategy.this.afterCoroutineSuspended(threadId);
            }

            @Override
            public void beforeCoroutineResumed(int threadId) {
                ManagedStrategy.this.beforeCoroutineResumed(threadId);
                super.beforeCoroutineResumed(threadId);
            }
        };
        initializeManagedState();
    }

    @Override
    public ClassVisitor createTransformer(ClassVisitor cv) {
        return new ManagedStrategyTransformer(
                cv,
                codeLocationConstructors,
                guarantees,
                shouldMakeStateRepresentation,
                eliminateLocalObjects,
                loggingEnabled
        );
    }

    @Override
    public Remapper createRemapper() {
        return new ManagedStrategyTransformer.JavaUtilRemapper();
    }

    @Override
    public boolean needsTransformation() {
        return true;
    }

    @Override
    public final LincheckFailure run() {
        try {
            return runImpl();
        } finally {
            runner.close();
        }
    }

    /**
     * This method implements the strategy logic
     */
    protected abstract LincheckFailure runImpl();

    // == LISTENING EVENTS ==

    /**
     * This method is executed as the first thread action.
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     */
    public void onStart(int threadId) {}

    /**
     * This method is executed as the last thread action if no exception has been thrown.
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     */
    public void onFinish(int threadId) {}

    /**
     * This method is executed if an exception has been thrown.
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     */
    public void onFailure(int threadId, Throwable e) {}

    /**
     * This method is executed before a shared variable read operation.
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     */
    public void beforeSharedVariableRead(int threadId, int codeLocation) {}

    /**
     * This method is executed before a shared variable write operation.
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     */
    public void beforeSharedVariableWrite(int threadId, int codeLocation) {}

    /**
     * This method is executed before an atomic method call.
     * Atomic method is a method that is marked by ManagedGuarantee to be treated as atomic.
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     */
    public void beforeAtomicMethodCall(int threadId, int codeLocation) {}

    /**
     *
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param monitor
     * @return whether lock should be actually acquired
     */
    public boolean beforeLockAcquire(int threadId, int codeLocation, Object monitor) {
        return true;
    }

    /**
     *
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param monitor
     * @return whether lock should be actually released
     */
    public boolean beforeLockRelease(int threadId, int codeLocation, Object monitor) {
        return true;
    }

    /**
     *
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout {@code true} if is invoked with timeout, {@code false} otherwise.
     * @return whether park should be executed
     */
    public boolean beforePark(int threadId, int codeLocation, boolean withTimeout) {
        return true;
    }

    /**
     *
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param thread
     */
    public void afterUnpark(int threadId, int codeLocation, Object thread) {}

    /**
     *
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param monitor
     * @param withTimeout {@code true} if is invoked with timeout, {@code false} otherwise.
     * @return whether wait should be executed
     */
    public boolean beforeWait(int threadId, int codeLocation, Object monitor, boolean withTimeout) {
        return true;
    }

    /**
     *
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param monitor
     * @param notifyAll
     */
    public void afterNotify(int threadId, int codeLocation, Object monitor, boolean notifyAll) {}

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended
     * @param threadId number of invoking thread
     */
    public void afterCoroutineSuspended(int threadId) {}

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed
     * @param threadId number of invoking thread
     */
    public void beforeCoroutineResumed(int threadId) {}

    /**
     * This method is invoked by a test thread
     * before each ignored section start.
     * These sections are determined by Strategy.ignoredEntryPoints()
     * @param threadId number of invoking thread
     */
    public void enterIgnoredSection(int threadId) {}

    /**
     * This method is invoked by a test thread
     * after each ignored section end.
     * @param threadId number of invoking thread
     */
    public void leaveIgnoredSection(int threadId) {}

    /**
     * This method is invoked by a test thread
     * before each method invocation.
     * @param codeLocation the byte-code location identifier of this invocation
     * @param threadId number of invoking thread
     */
    public void beforeMethodCall(int threadId, int codeLocation) {}

    /**
     * This method is invoked by a test thread
     * after each method invocation.
     * @param threadId number of invoking thread
     * @param codeLocation the byte-code location identifier of this invocation
     */
    public void afterMethodCall(int threadId, int codeLocation) {}


    /**
     * This method is invoked by a test thread
     * after each write or atomic method invocation
     * @param threadId
     */
    public void makeStateRepresentation(int threadId) {}

    // == LOGGING METHODS ==

    /**
     * Returns a {@link CodeLocation} which describes the specified code location
     *
     * @param codeLocation code location identifier which is inserted by transformer
     */
    public final CodeLocation getLocationDescription(int codeLocation) {
        return codeLocations.get(codeLocation);
    }

    /**
     * Creates a new {@link CodeLocation}.
     * The type of the created code location is defined by the used constructor.
     * @param constructorId which constructor to use for createing code location
     * @return index of the created code location
     */
    public final int createCodeLocation(int constructorId) {
        codeLocations.add(codeLocationConstructors.get(constructorId).invoke());
        return codeLocations.size() - 1;
    }

    /**
     * Returns index of the last code location.
     */
    public final int lastCodeLocationIndex() {
        return codeLocations.size() - 1;
    }


    // == UTILITY METHODS ==

    /**
     * This method is invoked by transformed via {@link ManagedStrategyTransformer} code,
     * it helps to determine the number of thread we are executing on.
     *
     * @return the number of the current thread according to the {@link ExecutionScenario execution scenario}.
     */
    public int currentThreadNumber() {
        Thread t = Thread.currentThread();
        if (t instanceof FixedActiveThreadsExecutor.TestThread) {
            return ((FixedActiveThreadsExecutor.TestThread) t).getThreadId();
        } else {
            return nThreads;
        }
    }

    protected void initializeManagedState() {
        ManagedStateHolder.setState(runner.classLoader, this);
    }
}
