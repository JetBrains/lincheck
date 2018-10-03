package com.devexperts.dxlab.lincheck.strategy.stress;

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

import com.devexperts.dxlab.lincheck.CTestConfiguration;
import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.execution.ExecutionGenerator;
import com.devexperts.dxlab.lincheck.execution.ExecutionScenario;
import com.devexperts.dxlab.lincheck.execution.RandomExecutionGenerator;
import com.devexperts.dxlab.lincheck.verifier.Verifier;
import com.devexperts.dxlab.lincheck.verifier.linearizability.LinearizabilityVerifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation configures concurrent test using stress strategy.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(StressCTest.StressCTests.class)
@Inherited
public @interface StressCTest {
    /**
     * Number of different test scenarios to be executed
     */
    int iterations() default CTestConfiguration.DEFAULT_ITERATIONS;

    /**
     * Run each test scenario {@code invocations} times.
     */
    int invocationsPerIteration() default StressCTestConfiguration.DEFAULT_INVOCATIONS;

    /**
     * Use the specified number of threads for the parallel part of an execution.
     * <p>
     * Note, that the the actual number of threads can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#parallelExecution
     */
    int threads() default CTestConfiguration.DEFAULT_THREADS;

    /**
     * Generate the specified number of operations for each thread of the parallel part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#parallelExecution
     */
    int actorsPerThread() default CTestConfiguration.DEFAULT_ACTORS_PER_THREAD;

    /**
     * Generate the specified number of operation for the initial sequential part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#initExecution
     */
    int actorsBefore() default CTestConfiguration.DEFAULT_ACTORS_BEFORE;

    /**
     * Generate the specified number of operation for the last sequential part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#postExecution
     */
    int actorsAfter() default CTestConfiguration.DEFAULT_ACTORS_AFTER;

    /**
     * Use the specified execution generator
     */
    Class<? extends ExecutionGenerator> generator() default RandomExecutionGenerator.class;

    /**
     * Use the specified verifier
     */
    Class<? extends Verifier> verifier() default LinearizabilityVerifier.class;

    /**
     * Holder annotation for {@link StressCTest}.
     * Not a public API.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    @interface StressCTests {
        StressCTest[] value();
    }
}

