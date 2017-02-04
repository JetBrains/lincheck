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

import com.devexperts.dxlab.lincheck.annotations.CTest;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Test configuration which is passed via {@link CTest} annotation.
 */
class CTestConfiguration {
    private final int iterations;
    private final int invocationsPerIteration;
    private final List<TestThreadConfiguration> threadConfigurations;

    private CTestConfiguration(int iterations, int invocationsPerIteration, List<TestThreadConfiguration> threadConfigurations) {
        this.iterations = iterations;
        this.invocationsPerIteration = invocationsPerIteration;
        this.threadConfigurations = threadConfigurations;
    }

    static List<CTestConfiguration> getFromTestClass(Class<?> testClass) {
        return Arrays.stream(testClass.getAnnotationsByType(CTest.class))
            .map(cTestAnn -> new CTestConfiguration(cTestAnn.iterations(), cTestAnn.invocationsPerIteration(), createTestThreadConfigurations(cTestAnn)))
            .collect(Collectors.toList());
    }

    private static List<TestThreadConfiguration> createTestThreadConfigurations(CTest cTestAnn) {
        return Arrays.stream(cTestAnn.actorsPerThread())
            .map(conf -> {
                String[] cs = conf.split(":");
                if (cs.length != 2) {
                    throw new IllegalArgumentException(
                        "Thread configuration in @CTest should be in \"<min>:<max>\" format");
                }
                return new TestThreadConfiguration(Integer.parseInt(cs[0]), Integer.parseInt(cs[1]));
            })
            .collect(Collectors.toList());
    }

    int getIterations() {
        return iterations;
    }

    int getThreads() {
        return threadConfigurations.size();
    }

    int getInvocationsPerIteration() {
        return invocationsPerIteration;
    }

    List<TestThreadConfiguration> getThreadConfigurations() {
        return threadConfigurations;
    }

    static class TestThreadConfiguration {
        final int minActors;
        final int maxActors;

        private TestThreadConfiguration(int minActors, int maxActors) {
            this.minActors = minActors;
            this.maxActors = maxActors;
        }
    }
}