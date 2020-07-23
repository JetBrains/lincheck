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

import kotlin.jvm.functions.Function0;
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.strategy.*;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTestConfiguration;
import org.jetbrains.kotlinx.lincheck.verifier.Verifier;
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.UnaryOperator.identity;
import static org.jetbrains.kotlinx.lincheck.UtilsKt.chooseSequentialSpecification;
import static org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration.DEFAULT_GUARANTEES;

/**
 * Configuration of an abstract concurrent test.
 * Should be overridden for every strategy.
 */
public abstract class CTestConfiguration {
    public static final int DEFAULT_ITERATIONS = 200;
    public static final int DEFAULT_THREADS = 2;
    public static final int DEFAULT_ACTORS_PER_THREAD = 5;
    public static final int DEFAULT_ACTORS_BEFORE = 5;
    public static final int DEFAULT_ACTORS_AFTER = 5;
    public static final Class<? extends ExecutionGenerator> DEFAULT_EXECUTION_GENERATOR = RandomExecutionGenerator.class;
    public static final Class<? extends Verifier> DEFAULT_VERIFIER = LinearizabilityVerifier.class;
    public static final boolean DEFAULT_MINIMIZE_ERROR = true;
    public static final long DEFAULT_TIMEOUT_MS = 10_000;

    public final Class<?> testClass;
    public final int iterations;
    public final int threads;
    public final int actorsPerThread;
    public final int actorsBefore;
    public final int actorsAfter;
    public final Class<? extends ExecutionGenerator> generatorClass;
    public final Class<? extends Verifier> verifierClass;
    public final boolean requireStateEquivalenceImplCheck;
    public final Boolean minimizeFailedScenario;
    public final Class<?> sequentialSpecification;
    public final long timeoutMs;

    protected CTestConfiguration(Class<?> testClass, int iterations, int threads, int actorsPerThread, int actorsBefore, int actorsAfter,
        Class<? extends ExecutionGenerator> generatorClass, Class<? extends Verifier> verifierClass,
        boolean requireStateEquivalenceImplCheck, boolean minimizeFailedScenario, Class<?> sequentialSpecification, long timeoutMs)
    {
        this.testClass = testClass;
        this.iterations = iterations;
        this.threads = threads;
        this.actorsPerThread = actorsPerThread;
        this.actorsBefore = actorsBefore;
        this.actorsAfter = actorsAfter;
        this.generatorClass = generatorClass;
        this.verifierClass = verifierClass;
        this.requireStateEquivalenceImplCheck = requireStateEquivalenceImplCheck;
        this.minimizeFailedScenario = minimizeFailedScenario;
        this.sequentialSpecification = sequentialSpecification;
        this.timeoutMs = timeoutMs;
    }

    protected abstract Strategy createStrategy(Class<?> testClass, ExecutionScenario scenario,
                                               List<Method> validationFunctions, Method stateRepresentation, Verifier verifier);

    static List<CTestConfiguration> createFromTestClassAnnotations(Class<?> testClass) {
        Stream<CTestConfiguration> stressConfigurations = Arrays.stream(testClass.getAnnotationsByType(StressCTest.class))
            .map(ann -> new StressCTestConfiguration(testClass, ann.iterations(),
                    ann.threads(), ann.actorsPerThread(), ann.actorsBefore(), ann.actorsAfter(),
                    ann.generator(), ann.verifier(), ann.invocationsPerIteration(), true,
                    ann.requireStateEquivalenceImplCheck(), ann.minimizeFailedScenario(),
                    chooseSequentialSpecification(ann.sequentialSpecification(), testClass), DEFAULT_TIMEOUT_MS));
        Stream<CTestConfiguration> modelCheckingConfigurations = Arrays.stream(testClass.getAnnotationsByType(ModelCheckingCTest.class))
            .map(ann -> new ModelCheckingCTestConfiguration(testClass, ann.iterations(),
                    ann.threads(), ann.actorsPerThread(), ann.actorsBefore(), ann.actorsAfter(),
                    ann.generator(), ann.verifier(), ann.checkObstructionFreedom(), ann.hangingDetectionThreshold(),
                    ann.invocationsPerIteration(), DEFAULT_GUARANTEES, ann.requireStateEquivalenceImplCheck(),
                    ann.minimizeFailedScenario(),  chooseSequentialSpecification(ann.sequentialSpecification(), testClass), DEFAULT_TIMEOUT_MS));

        return Stream.of(stressConfigurations, modelCheckingConfigurations).flatMap(identity()).collect(Collectors.toList());
    }
}