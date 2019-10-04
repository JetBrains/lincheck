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

import com.devexperts.jagent.ClassInfo;
import org.jetbrains.kotlinx.lincheck.Reporter;
import org.jetbrains.kotlinx.lincheck.TestReport;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario;
import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner;
import org.jetbrains.kotlinx.lincheck.runner.Runner;
import org.jetbrains.kotlinx.lincheck.util.Either;
import org.jetbrains.kotlinx.lincheck.verifier.Verifier;
import org.objectweb.asm.ClassVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * This is an abstract class for all strategies with transformers.
 * This abstraction helps to transform byte-code in order to insert required for
 * managing instructions and to hide class loading problems from the strategy algorithm.
 */
public abstract class ManagedStrategy extends Strategy {
    /**
     * Number of threads
     */
    protected final int nThreads;

    protected final Runner runner;
    private ManagedStrategyTransformer transformer;

    protected ManagedStrategy(Class<?> testClass, ExecutionScenario scenario, Verifier verifier, Reporter reporter) {
        super(scenario, verifier, reporter);
        nThreads = scenario.parallelExecution.size();
        runner = new ParallelThreadsRunner(scenario, this, testClass, null) {
            @Override
            public void onStart(int iThread) {
                super.onStart(iThread);
                ManagedStrategy.this.onStart(iThread);
            }

            @Override
            public void onFinish(int iThread) {
                ManagedStrategy.this.onFinish(iThread);
                super.onFinish(iThread);
            }

            @Override
            public void onException(int iThread, Throwable e) {
                ManagedStrategy.this.onException(iThread, e);
                super.onException(iThread, e);
            }

            @Override
            public void afterCoroutineSuspended(int iThread) {
                super.afterCoroutineSuspended(iThread);
                ManagedStrategy.this.afterCoroutineSuspended(iThread);
            }

            @Override
            public void beforeCoroutineResumed(int iThread) {
                ManagedStrategy.this.beforeCoroutineResumed(iThread);
                super.beforeCoroutineResumed(iThread);
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
    public final TestReport run() throws Exception {
        try {
            return runImpl();
        } finally {
            runner.close();
        }
    }

    /**
     * Runs next invocation with the same {@link ExecutionScenario scenario}.
     *
     * @return invocation results for each executed actor.
     */
    protected final Either<TestReport, ExecutionResult> runInvocation() throws InterruptedException {
        return runner.run();
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
    protected abstract TestReport runImpl() throws Exception;

    // == LISTENING EVENTS ==

    /**
     * This method is executed as the first thread action.
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     */
    public void onStart(int iThread) {}

    /**
     * This method is executed as the last thread action if no exception has been thrown.
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     */
    public void onFinish(int iThread) {}

    /**
     * This method is executed if an exception has been thrown.
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     */
    public void onException(int iThread, Throwable e) {}

    /**
     * This method is executed before a shared variable read operation.
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     */
    public void beforeSharedVariableRead(int iThread, int codeLocation) {}

    /**
     * This method is executed before a shared variable write operation (including CAS).
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     */
    public void beforeSharedVariableWrite(int iThread, int codeLocation) {}

    /**
     *
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param monitor
     * @return whether lock should be actually acquired
     */
    public boolean beforeLockAcquire(int iThread, int codeLocation, Object monitor) {
        return true;
    }

    /**
     *
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param monitor
     * @return whether lock should be actually released
     */
    public boolean beforeLockRelease(int iThread, int codeLocation, Object monitor) {
        return true;
    }

    /**
     *
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout {@code true} if is invoked with timeout, {@code false} otherwise.
     * @return whether park should be executed
     */
    public boolean beforePark(int iThread, int codeLocation, boolean withTimeout) {
        return true;
    }

    /**
     *
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param thread
     */
    public void afterUnpark(int iThread, int codeLocation, Object thread) {}

    /**
     *
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param monitor
     * @param withTimeout {@code true} if is invoked with timeout, {@code false} otherwise.
     * @return whether wait should be executed
     */
    public boolean beforeWait(int iThread, int codeLocation, Object monitor, boolean withTimeout) {
        return true;
    }

    /**
     *
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param monitor
     * @param notifyAll
     */
    public void afterNotify(int iThread, int codeLocation, Object monitor, boolean notifyAll) {}

    /**
     *
     * @param iThread the number of the executed thread according to the {@link ExecutionScenario scenario}.
     * @param codeLocation the byte-code location identifier of this operation.
     * @param iInterruptedThread
     */
    public void afterThreadInterrupt(int iThread, int codeLocation, int iInterruptedThread) {}

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended
     * @param iThread number of invoking thread
     */
    public void afterCoroutineSuspended(int iThread) {}

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed
     * @param iThread number of invoking thread
     */
    public void beforeCoroutineResumed(int iThread) {}

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
            return ((ParallelThreadsRunner.TestThread) t).getIThread();
        } else {
            return nThreads;
        }
    }
}
