/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking;

import kotlin.Deprecated;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.annotations.DummySequentialSpecification;
import org.jetbrains.lincheck.datastructures.CTestConfiguration;
import org.jetbrains.lincheck.datastructures.verifier.Verifier;
import org.jetbrains.lincheck.datastructures.verifier.LinearizabilityVerifier;

import java.lang.annotation.*;

import static org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration.*;

/**
 * This annotation configures concurrent test using {@link ModelCheckingStrategy managed} strategy.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ModelCheckingCTest.ModelCheckingCTests.class)
@Inherited
public @interface ModelCheckingCTest {
    /**
     * The number of different test scenarios to be executed
     */
    int iterations() default CTestConfiguration.DEFAULT_ITERATIONS;

    /**
     * Use the specified number of threads for the parallel part of an execution.
     * <p>
     * Note, that the the actual number of threads can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#getParallelExecution
     */
    int threads() default CTestConfiguration.DEFAULT_THREADS;

    /**
     * Generate the specified number of operations for each thread of the parallel part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#getParallelExecution
     */
    int actorsPerThread() default CTestConfiguration.DEFAULT_ACTORS_PER_THREAD;

    /**
     * Generate the specified number of operation for the initial sequential part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#getInitExecution
     */
    int actorsBefore() default CTestConfiguration.DEFAULT_ACTORS_BEFORE;

    /**
     * Generate the specified number of operation for the last sequential part of an execution.
     * <p>
     * Note, that the the actual number of operations can be less due to some restrictions
     * like {@link Operation#runOnce()}.
     *
     * @see ExecutionScenario#getPostExecution
     */
    int actorsAfter() default CTestConfiguration.DEFAULT_ACTORS_AFTER;

    /**
     * Use the specified execution generator.
     */
    Class<? extends ExecutionGenerator> generator() default RandomExecutionGenerator.class;

    /**
     * Use the specified verifier.
     */
    Class<? extends Verifier> verifier() default LinearizabilityVerifier.class;

    /**
     * Check the testing concurrent algorithm for obstruction freedom.
     */
    boolean checkObstructionFreedom() default false;

    /**
     * Use the specified maximum number of the same event repetitions to detect "hangs".
     * In this case, the strategy is either forced to switch the current thread, or report
     * the obstruction-freedom violation if {@link ModelCheckingCTest#checkObstructionFreedom} is enabled.
     */
    int hangingDetectionThreshold() default DEFAULT_HANGING_DETECTION_THRESHOLD;

    /**
     * The maximal number of invocations that the managed strategy can use to search for finding an incorrect execution.
     * It is also possible that the strategy explores all the possible interleavings with fewer invocations.
     */
    int invocationsPerIteration() default DEFAULT_INVOCATIONS;

    /**
     * Does nothing, states equivalence does not always improve performance of verification.
     * <p>
     * Require correctness check of test instance state equivalency relation, which is defined by the user.
     * Essentially, it checks whether two new instances of the test class are equal.
     * If the check fails, an {@link IllegalStateException} is thrown.
     */
    @Deprecated(message = "Does nothing, because equals/hashcode don't always improve performance of verification")
    boolean requireStateEquivalenceImplCheck() default false;

    /**
     * If this feature is enabled and an invalid interleaving has been found,
     * *lincheck* tries to minimize the corresponding scenario in order to
     * construct a smaller one so that the test fails on it as well.
     * Enabled by default.
     */
    boolean minimizeFailedScenario() default true;

    /**
     * The specified class defines the sequential behavior of the testing data structure;
     * it is used by {@link Verifier} to build a labeled transition system,
     * and should have the same methods as the testing data structure.
     * However, some verifiers require additional parameters for these methods.
     * By default, the provided concurrent implementation is used in a sequential way.
     */
    Class<?> sequentialSpecification() default DummySequentialSpecification.class;

    /**
     * Holder annotation for {@link ModelCheckingCTest}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Inherited
    @interface ModelCheckingCTests {
        ModelCheckingCTest[] value();
    }
}