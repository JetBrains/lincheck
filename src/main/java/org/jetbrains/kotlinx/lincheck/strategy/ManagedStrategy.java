package org.jetbrains.kotlinx.lincheck.strategy;

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

import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.runner.*;
import org.objectweb.asm.*;

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
    private ManagedStrategyTransformer transformer;

    protected ManagedStrategy(Class<?> testClass, ExecutionScenario scenario, List<Method> validationFunctions) {
        super(scenario);
        nThreads = scenario.parallelExecution.size();
        runner = new ParallelThreadsRunner(this, testClass, validationFunctions,null) {
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
        ManagedStrategyHolder.setStrategy(runner.classLoader, this);
    }

    @Override
    public ClassVisitor createTransformer(ClassVisitor cv) {
        List<StackTraceElement> previousCodeLocations;
        if (transformer == null) {
            previousCodeLocations = new ArrayList<>();
        } else {
            previousCodeLocations = transformer.getCodeLocations();
        }
        return transformer = new ManagedStrategyTransformer(cv, previousCodeLocations);
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
     * Returns a {@link StackTraceElement} described the specified code location
     *
     * @param codeLocation code location identifier which is inserted by transformer
     */
    protected final StackTraceElement getLocationDescription(int codeLocation) {
        return transformer.getCodeLocations().get(codeLocation);
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
     * This method is executed before a shared variable write operation (including CAS).
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     */
    public void beforeSharedVariableWrite(int threadId, int codeLocation) {}

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
     *
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param iInterruptedThread
     */
    public void afterThreadInterrupt(int threadId, int codeLocation, int iInterruptedThread) {}

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
     * This method is invoked before start of each actor.
     * @param threadId the number of the executed thread according to the {@link ExecutionScenario scenario}.
     */
    public void startNewActor(int threadId) {}

    /**
     * This method is invoked by a test thread
     * before a class initialization start
     * @param threadId number of invoking thread
     */
    public void beforeClassInitialization(int threadId) {}

    /**
     * This method is invoked by a test thread
     * after a class initialization end
     * @param threadId number of invoking thread
     */
    public void afterClassInitialization(int threadId) {}

    // == UTILITY METHODS

    /**
     * This method is invoked by transformed via {@link ManagedStrategyTransformer} code,
     * it helps to determine the number of thread we are executing on.
     *
     * @return the number of the current thread according to the {@link ExecutionScenario execution scenario}.
     */
    public int currentThreadNumber() {
        Thread t = Thread.currentThread();
        if (t instanceof ParallelThreadsRunner.TestThread) {
            return ((ParallelThreadsRunner.TestThread) t).getThreadId();
        } else {
            return nThreads;
        }
    }
}
