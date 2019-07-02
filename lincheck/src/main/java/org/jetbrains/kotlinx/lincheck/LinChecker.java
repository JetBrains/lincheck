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

import org.jetbrains.kotlinx.lincheck.annotations.LogLevel;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario;
import org.jetbrains.kotlinx.lincheck.strategy.Strategy;
import org.jetbrains.kotlinx.lincheck.verifier.Verifier;

import java.util.Collections;
import java.util.List;

import static org.jetbrains.kotlinx.lincheck.ReporterKt.DEFAULT_LOG_LEVEL;


/**
 * This class runs concurrent tests.
 * See {@link #check(Class)} and {@link #check(Class, Options)} methods for details.
 */
public class LinChecker {
    private final Class<?> testClass;
    private final List<? extends CTestConfiguration> testConfigurations;
    private final CTestStructure testStructure;
    private final Reporter reporter;

    private LinChecker(Class<?> testClass, Options options) {
        this.testClass = testClass;
        this.testStructure = CTestStructure.getFromTestClass(testClass);
        LoggingLevel logLevel;
        if (options != null) {
            logLevel= options.logLevel;
            this.testConfigurations = Collections.singletonList(options.createTestConfigurations());
        } else {
            logLevel = getLogLevelFromAnnotation();
            this.testConfigurations = CTestConfiguration.createFromTestClass(testClass);
        }
        this.reporter = new Reporter(logLevel);
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
     * Runs concurrent test on specified class with the specified by options environment.
     * <p>
     * NOTE: this method ignores {@code @<XXX>CTest} annotations on the test class.
     *
     * @throws AssertionError if algorithm or data structure is not correct.
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
            } catch (RuntimeException | AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void checkImpl(CTestConfiguration testCfg) throws AssertionError, Exception {
        ExecutionGenerator exGen = createExecutionGenerator(testCfg.generatorClass, testCfg);
        // Run iterations
        for (int iteration = 1; iteration <= testCfg.iterations; iteration++) {
            ExecutionScenario scenario = exGen.nextExecution();
            reporter.logIteration(iteration, testCfg.iterations, scenario);
            Verifier verifier = createVerifier(testCfg.verifierClass, scenario, testClass);
            Strategy strategy = Strategy.createStrategy(testCfg, testClass, scenario, verifier, reporter);
            strategy.run();
        }
    }

    private Verifier createVerifier(Class<? extends Verifier> verifierClass, ExecutionScenario scenario,
        Class<?> testClass) throws Exception
    {
        return verifierClass.getConstructor(ExecutionScenario.class, Class.class)
            .newInstance(scenario, testClass);
    }

    private ExecutionGenerator createExecutionGenerator(Class<? extends ExecutionGenerator> generatorClass,
        CTestConfiguration testConfiguration) throws Exception
    {
        return generatorClass.getConstructor(CTestConfiguration.class, CTestStructure.class)
            .newInstance(testConfiguration, testStructure);
    }

    private LoggingLevel getLogLevelFromAnnotation() {
        LogLevel logLevelAnn = testClass.getAnnotation(LogLevel.class);
        if (logLevelAnn == null)
            return DEFAULT_LOG_LEVEL;
        return logLevelAnn.value();
    }
}