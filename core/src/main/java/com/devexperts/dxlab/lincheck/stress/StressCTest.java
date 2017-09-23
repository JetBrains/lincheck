package com.devexperts.dxlab.lincheck.stress;

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

import com.devexperts.dxlab.lincheck.CTestConfiguration;
import com.devexperts.dxlab.lincheck.execution.ExecutionGenerator;
import com.devexperts.dxlab.lincheck.execution.RandomExecutionGenerator;
import com.devexperts.dxlab.lincheck.verifier.LinearizabilityVerifier;
import com.devexperts.dxlab.lincheck.verifier.Verifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation configures concurrent test using stress strategy.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(StressCTests.class)
public @interface StressCTest {
    /**
     * Number of different test scenarios to be executed
     */
    int iterations() default CTestConfiguration.DEFAULT_ITERATIONS;

    /**
     * todo
     */
    String[] actorsPerThread() default {};

    /**
     * Run one test scenario {@code invocations} times.
     */
    int invocationsPerIteration() default StressCTestConfiguration.DEFAULT_INVOCATIONS;

    /**
     * Use specified execution generator
     */
    Class<? extends ExecutionGenerator> generator() default RandomExecutionGenerator.class;

    /**
     * Use specified verifier
     */
    Class<? extends Verifier> verifier() default LinearizabilityVerifier.class;
}

