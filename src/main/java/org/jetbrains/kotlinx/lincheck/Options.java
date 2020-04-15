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
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario;
import org.jetbrains.kotlinx.lincheck.verifier.Verifier;

import static org.jetbrains.kotlinx.lincheck.ReporterKt.DEFAULT_LOG_LEVEL;

/**
 * Abstract class for test options.
 */
public abstract class Options<OPT extends Options, CTEST extends CTestConfiguration> {
    protected LoggingLevel logLevel = DEFAULT_LOG_LEVEL;
    protected int iterations = CTestConfiguration.DEFAULT_ITERATIONS;
    protected int threads = CTestConfiguration.DEFAULT_THREADS;
    protected int actorsPerThread = CTestConfiguration.DEFAULT_ACTORS_PER_THREAD;
    protected int actorsBefore = CTestConfiguration.DEFAULT_ACTORS_BEFORE;
    protected int actorsAfter = CTestConfiguration.DEFAULT_ACTORS_AFTER;
    protected Class<? extends ExecutionGenerator> executionGenerator = CTestConfiguration.DEFAULT_EXECUTION_GENERATOR;
    protected Class<? extends Verifier> verifier = CTestConfiguration.DEFAULT_VERIFIER;
    protected boolean requireStateEquivalenceImplementationCheck = true;
    protected boolean minimizeFailedScenario = CTestConfiguration.DEFAULT_MINIMIZE_ERROR;
    protected Class<?> sequentialSpecification = null;

    /**
     * Number of different test scenarios to be executed
     */
    public OPT iterations(int iterations) {
        this.iterations = iterations;
        return (OPT) this;
    }

    /**
     * Use the specified number of threads for the parallel part of an execution.
     * <p>
     * Note, that the the actual number of threads can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#parallelExecution
     */
    public OPT threads(int threads) {
        this.threads = threads;
        return (OPT) this;
    }

    /**
     * Generate the specified number of operations for each thread of the parallel part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#parallelExecution
     */
    public OPT actorsPerThread(int actorsPerThread) {
        this.actorsPerThread = actorsPerThread;
        return (OPT) this;
    }

    /**
     * Generate the specified number of operation for the initial sequential part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#initExecution
     */
    public OPT actorsBefore(int actorsBefore) {
        this.actorsBefore = actorsBefore;
        return (OPT) this;
    }

    /**
     * Generate the specified number of operation for the last sequential part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#postExecution
     */
    public OPT actorsAfter(int actorsAfter) {
        this.actorsAfter = actorsAfter;
        return (OPT) this;
    }

    /**
     * Use the specified execution generator
     */
    public OPT executionGenerator(Class<? extends ExecutionGenerator> executionGenerator) {
        this.executionGenerator = executionGenerator;
        return (OPT) this;
    }

    /**
     * Use the specified verifier
     */
    public OPT verifier(Class<? extends Verifier> verifier) {
        this.verifier = verifier;
        return (OPT) this;
    }

    /**
     * Require correctness check of test instance state equivalency relation defined by the user.
     * It checks whether two new instances of a test class are equal.
     * If the check fails [{@link IllegalStateException}] is thrown.
     */
    public OPT requireStateEquivalenceImplCheck(boolean require) {
        this.requireStateEquivalenceImplementationCheck = require;
        return (OPT) this;
    }

    /**
     * If this feature is enabled and an invalid interleaving has been found,
     * *lincheck* tries to minimize the corresponding scenario in order to
     * construct a smaller one so that the test fails on it as well.
     * Enabled by default.
     */
    public OPT minimizeFailedScenario(boolean minimizeFailedScenario) {
        this.minimizeFailedScenario = minimizeFailedScenario;
        return (OPT) this;
    }

    public abstract CTEST createTestConfigurations(Class<?> testClass);

    /**
     * Set logging level, {@link DEFAULT_LOG_LEVEL} is used by default.
     */
    public OPT logLevel(LoggingLevel logLevel) {
        this.logLevel = logLevel;
        return (OPT) this;
    }


    /**
     * The specified class defines the sequential behavior of the testing data structure;
     * it is used by {@link Verifier} to build a labeled transition system,
     * and should have the same methods as the testing data structure.
     *
     * By default, the provided concurrent implementation is used in a sequential way.
     */
    public OPT sequentialSpecification(Class<?> clazz) {
        this.sequentialSpecification = clazz;
        return (OPT) this;
    }
}
