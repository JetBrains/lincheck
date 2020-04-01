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
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.strategy.Strategy;
import org.jetbrains.kotlinx.lincheck.verifier.*;
import static org.jetbrains.kotlinx.lincheck.ReporterKt.DEFAULT_LOG_LEVEL;
import java.util.*;


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
            logLevel = options.logLevel;
            this.testConfigurations = Collections.singletonList(options.createTestConfigurations(testClass));
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
            // Check correctness of the generated scenario
            reporter.logIteration(iteration, testCfg.iterations, scenario);
            try {
                runScenario(scenario, testCfg);
            } catch (AssertionError e) {
                if (!testCfg.minimizeFailedScenario) throw e;
                minimizeScenario(scenario, testCfg, e);
            }
        }
    }

    // Tries to minimize the specified failing scenario to make the error easier to understand.
    // The algorithm is greedy: it tries to remove one actor from the scenario and checks
    // whether a test with the modified one fails with error as well. If it fails,
    // then the scenario has been successfully minimized, and the algorithm tries to minimize it again, recursively.
    // Otherwise, if no actor can be removed so that the generated test fails, the minimization is completed.
    // Thus, the algorithm works in the linear time of the total number of actors.
    private void minimizeScenario(ExecutionScenario scenario, CTestConfiguration testCfg, AssertionError currentError) throws AssertionError, Exception {
        reporter.logScenarioMinimization(scenario);
        for (int i = 0; i < scenario.parallelExecution.size(); i++) {
            for (int j = 0; j < scenario.parallelExecution.get(i).size(); j++) {
                ExecutionScenario newScenario = copyScenario(scenario);
                newScenario.parallelExecution.get(i).remove(j);
                if (newScenario.parallelExecution.get(i).isEmpty())
                    newScenario.parallelExecution.remove(i); // remove empty thread
                minimizeNewScenarioAttempt(newScenario, testCfg);
            }
        }
        for (int i = 0; i < scenario.initExecution.size(); i++) {
            ExecutionScenario newScenario = copyScenario(scenario);
            newScenario.initExecution.remove(i);
            minimizeNewScenarioAttempt(newScenario, testCfg);
        }
        for (int i = 0; i < scenario.postExecution.size(); i++) {
            ExecutionScenario newScenario = copyScenario(scenario);
            newScenario.postExecution.remove(i);
            minimizeNewScenarioAttempt(newScenario, testCfg);
        }
        throw currentError;
    }

    private void minimizeNewScenarioAttempt(ExecutionScenario newScenario, CTestConfiguration testCfg) throws AssertionError, Exception {
        try {
            runScenario(newScenario, testCfg);
        } catch (IllegalArgumentException e) {
            // Ignore incorrect scenarios
        } catch (AssertionError e) {
            // Scenario has been minimized! Try to minimize more!
            minimizeScenario(newScenario, testCfg, e);
            throw new IllegalStateException("Never reaches, the previous `minimizeScenario` call always throw an exception");
        }
    }

    private ExecutionScenario copyScenario(ExecutionScenario scenario) {
        List<Actor> initExecution = new ArrayList<>(scenario.initExecution);
        List<List<Actor>> parallelExecution = new ArrayList<>();
        for (int i = 0; i < scenario.parallelExecution.size(); i++) {
            parallelExecution.add(new ArrayList<>(scenario.parallelExecution.get(i)));
        }
        List<Actor> postExecution = new ArrayList<>(scenario.postExecution);
        return new ExecutionScenario(initExecution, parallelExecution, postExecution);
    }

    private void runScenario(ExecutionScenario scenario, CTestConfiguration testCfg) throws AssertionError, Exception {
        validateScenario(testCfg, scenario);
        Verifier verifier = createVerifier(testCfg.verifierClass, testCfg.sequentialSpecification);
        if (testCfg.requireStateEquivalenceImplCheck) verifier.checkStateEquivalenceImplementation();
        Strategy strategy = Strategy.createStrategy(testCfg, testClass, scenario, verifier, reporter);
        strategy.run();
    }

    private void validateScenario(CTestConfiguration testCfg, ExecutionScenario scenario) {
        if (scenario.hasSuspendableActors()) {
            if (scenario.initExecution.stream().anyMatch(Actor::isSuspendable))
                throw new IllegalArgumentException("Generated execution scenario for the test class with suspendable methods contains suspendable actors in initial part");
            if (scenario.parallelExecution.stream().anyMatch(actors -> actors.stream().anyMatch(Actor::isSuspendable)) && scenario.postExecution.size() > 0)
                throw new IllegalArgumentException("Generated execution scenario for the test class with suspendable methods has non-empty post part");
        }
    }

    private Verifier createVerifier(Class<? extends Verifier> verifierClass, Class<?> sequentialSpecification) throws Exception {
        return verifierClass.getConstructor(Class.class).newInstance(sequentialSpecification);
    }

    private ExecutionGenerator createExecutionGenerator(Class<? extends ExecutionGenerator> generatorClass,
                                                        CTestConfiguration testConfiguration) throws Exception {
        return generatorClass.getConstructor(CTestConfiguration.class, CTestStructure.class).newInstance(testConfiguration, testStructure);
    }

    private LoggingLevel getLogLevelFromAnnotation() {
        LogLevel logLevelAnn = testClass.getAnnotation(LogLevel.class);
        if (logLevelAnn == null)
            return DEFAULT_LOG_LEVEL;
        return logLevelAnn.value();
    }
}