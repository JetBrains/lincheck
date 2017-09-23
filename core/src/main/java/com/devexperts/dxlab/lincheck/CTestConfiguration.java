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
import com.devexperts.dxlab.lincheck.execution.RandomExecutionGenerator;
import com.devexperts.dxlab.lincheck.stress.StressCTest;
import com.devexperts.dxlab.lincheck.stress.StressCTestConfiguration;
import com.devexperts.dxlab.lincheck.verifier.LinearizabilityVerifier;
import com.devexperts.dxlab.lincheck.verifier.Verifier;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Configuration of an abstract concurrent test.
 * Should be overridden for every strategy.
 */
public abstract class CTestConfiguration {
    public static final int DEFAULT_ITERATIONS = 200;
    public static final Class<? extends ExecutionGenerator> DEFAULT_EXECUTION_GENERATOR =
        RandomExecutionGenerator.class;
    public static final Class<? extends Verifier> DEFAULT_VERIFIER = LinearizabilityVerifier.class;

    public final int iterations;
    public final List<TestThreadConfiguration> threadConfigurations;
    public final Class<? extends ExecutionGenerator> generatorClass;
    public final Class<? extends Verifier> verifierClass;

    protected CTestConfiguration(int iterations, List<TestThreadConfiguration> threadConfigurations,
        Class<? extends ExecutionGenerator> generatorClass, Class<? extends Verifier> verifierClass)
    {
        this.iterations = iterations;
        this.generatorClass = generatorClass;
        this.verifierClass = verifierClass;
        if (!threadConfigurations.isEmpty()) {
            this.threadConfigurations = threadConfigurations;
        } else {
            this.threadConfigurations = defaultTestThreadConfigurations();
        }
    }

    private static List<TestThreadConfiguration> createTestThreadConfigurations(String[] actorsPerThreadDesc) {
        return Arrays.stream(actorsPerThreadDesc)
            .map(conf -> {
                String[] cs = conf.split(":");
                if (cs.length != 2) {
                    throw new IllegalArgumentException(
                        "Thread configuration in @StressCTest should be in \"<min>:<max>\" format");
                }
                return new TestThreadConfiguration(Integer.parseInt(cs[0]), Integer.parseInt(cs[1]));
            }).collect(Collectors.toList());
    }

    static List<StressCTestConfiguration> createFromTestClass(Class<?> testClass) {
        return Arrays.stream(testClass.getAnnotationsByType(StressCTest.class))
            .map(stressTestAnn -> new StressCTestConfiguration(stressTestAnn.iterations(),
                createTestThreadConfigurations(stressTestAnn.actorsPerThread()), stressTestAnn.generator(),
                stressTestAnn.verifier(), stressTestAnn.invocationsPerIteration())
            ).collect(Collectors.toList());
    }

    private List<TestThreadConfiguration> defaultTestThreadConfigurations() {
        return Arrays.asList(
            new TestThreadConfiguration(3, 5),
            new TestThreadConfiguration(3, 5)
        );
    }

    public int getThreads() {
        return threadConfigurations.size();
    }
}