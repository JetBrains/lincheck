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

import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.execution.ExecutionGenerator;
import com.devexperts.dxlab.lincheck.execution.ExecutionScenario;
import com.devexperts.dxlab.lincheck.verifier.Verifier;

import static com.devexperts.dxlab.lincheck.ReporterKt.DEFAULT_LOG_LEVEL;

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

    public abstract CTEST createTestConfigurations();

    /**
     * Set logging level, {@link DEFAULT_LOG_LEVEL} is used by default.
     */
    public OPT logLevel(LoggingLevel logLevel) {
        this.logLevel = logLevel;
        return (OPT) this;
    }
}
