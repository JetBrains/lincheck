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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * This class runs concurrent tests.
 * See {@link #check(Class)} and {@link #check(Class, Options)} methods for details.
 * TODO logging
 * TODO method after
 */
public class LinChecker {
    private final Class<?> testClass;
    private final List<? extends CTestConfiguration> testConfigurations;
    private final CTestStructure testStructure;

    private LinChecker(Class<?> testClass, Options options) {
        this.testClass = testClass;
        this.testStructure = CTestStructure.getFromTestClass(testClass);
        if (options != null) {
            this.testConfigurations = Collections.singletonList(options.createTestConfigurations());
        } else {
            this.testConfigurations = CTestConfiguration.createFromTestClass(testClass);
        }
    }

    /**
     * Runs all concurrent tests described with {@code @<XXX>CTest} annotations on the specified test class.
     *
     * @throws AssertionError if algorithm or data structure is not correct.
     */
    public static void check(Class<?> testClass) {
        check(testClass, null);
    }

    /**
     * Runs concurrent test on specified class. Provided options determines test configuration.
     * <p>
     * NOTE: this method ignores {@code @<XXX>CTest} annotations on the specified test class.
     *
     * @throws AssertionError if algorithm or data structure is not correct
     */
    public static void check(Class<?> testClass, Options options) {
        new LinChecker(testClass, options).check();
    }

    /**
     * @throws AssertionError if algorithm or data structure is not correct
     */
    private void check() throws AssertionError {
        if (testConfigurations.isEmpty()) {
            throw new IllegalStateException("No Lin-Check test configuration to run");
        }
        testConfigurations.forEach(testConfiguration -> {
            try {
                checkImpl(testConfiguration);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void checkImpl(CTestConfiguration testCfg) throws AssertionError, Exception {
        ExecutionGenerator exGen = createExecutionGenerator(testCfg.generatorClass, testCfg);
        // Run iterations
        for (int iteration = 1; iteration <= testCfg.iterations; iteration++) {
            System.out.println("= Iteration " + iteration + " / " + testCfg.iterations + " =");
            List<List<Actor>> actorsPerThread = exGen.nextExecution();
            logActorsPerThread(actorsPerThread);
            Object testInstance = testClass.newInstance();
            Verifier verifier = createVerifier(testCfg.verifierClass, actorsPerThread,
                testInstance, testStructure.resetMethod);
            Strategy strategy = Strategy.createStrategy(testCfg, testInstance, testStructure.resetMethod,
                actorsPerThread, verifier);
            strategy.run();
        }
    }

    private Verifier createVerifier(Class<? extends Verifier> verifierClass,
        List<List<Actor>> actorsPerThread, Object testInstance, Method resetMethod) throws Exception
    {
        return verifierClass.getConstructor(List.class, Object.class, Method.class)
            .newInstance(actorsPerThread, testInstance, resetMethod);
    }

    private ExecutionGenerator createExecutionGenerator(Class<? extends ExecutionGenerator> generatorClass,
        CTestConfiguration testConfiguration) throws Exception
    {
        return generatorClass.getConstructor(CTestConfiguration.class, CTestStructure.class)
            .newInstance(testConfiguration, testStructure);
    }

    private static void logActorsPerThread(List<List<Actor>> actorsPerThread) {
        System.out.println("Actors per thread:");
        actorsPerThread.forEach(System.out::println);
    }
}