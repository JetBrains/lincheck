package com.devexperts.dxlab.lincheck;

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

import com.devexperts.dxlab.lincheck.execution.ExecutionGenerator;
import com.devexperts.dxlab.lincheck.verifier.Verifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract class for test options.
 */
public abstract class Options<OPT extends Options, CTEST extends CTestConfiguration> {
    protected int iterations = 1_000;
    protected List<TestThreadConfiguration> threadConfigurations = new ArrayList<>();
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
     * Add one test thread with as minimum {@code minActors}
     * and as maximum {@code maxActors} actors to the configuration.
     */
    public OPT addThread(int minActors, int maxActors) {
        threadConfigurations.add(new TestThreadConfiguration(minActors, maxActors));
        return (OPT) this;
    }

    /**
     * Add {@code count} test threads with as minimum {@code minActors}
     * and as maximum {@code maxActors} actors to the configuration.
     */
    public OPT addThread(int minActors, int maxActors, int count) {
        for (int i = 0; i < count; i++) {
            addThread(minActors, maxActors);
        }
        return (OPT) this;
    }

    /**
     * Use specified execution generator
     */
    public OPT executionGenerator(Class<? extends ExecutionGenerator> executionGenerator) {
        this.executionGenerator = executionGenerator;
        return (OPT) this;
    }

    /**
     * Use specified verifier
     */
    public OPT verifier(Class<? extends Verifier> verifier) {
        this.verifier = verifier;
        return (OPT) this;
    }

    public abstract CTEST createTestConfigurations();
}
