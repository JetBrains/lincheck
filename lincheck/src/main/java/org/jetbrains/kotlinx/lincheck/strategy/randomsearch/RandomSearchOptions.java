/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy.randomsearch;

import org.jetbrains.kotlinx.lincheck.Options;

import static org.jetbrains.kotlinx.lincheck.CTestConfiguration.DEFAULT_MAX_REPETITIONS;
import static org.jetbrains.kotlinx.lincheck.UtilsKt.chooseSequentialSpecification;
import static org.jetbrains.kotlinx.lincheck.strategy.randomsearch.RandomSearchCTestConfiguration.DEFAULT_INVOCATIONS_PER_ITERATION;

/**
 * Options for {@link RandomSearchStrategy random search} strategy.
 */
public class RandomSearchOptions extends Options<RandomSearchOptions, RandomSearchCTestConfiguration> {
    protected ConcurrentGuarantee guarantee = ConcurrentGuarantee.NONE;
    protected int maxRepetitions = DEFAULT_MAX_REPETITIONS;
    protected int invocationsPerIteration = DEFAULT_INVOCATIONS_PER_ITERATION;

    /**
     * Check the specified guarantee of the concurrent algorithm
     */
    public RandomSearchOptions guarantee(ConcurrentGuarantee guarantee) {
        this.guarantee = guarantee;
        return this;
    }

    /**
     * Use the specified maximum number of repetitions to detect loops for checking concurrent guarantee
     */
    public RandomSearchOptions maxRepetitions(int maxRepetitions) {
        this.maxRepetitions = maxRepetitions;
        return this;
    }

    /**
     * Maximum number of invocationsPerIteration that managed strategy may use to search for incorrect execution
     */
    public RandomSearchOptions invocationsPerIteration(int invocationsPerIteration) {
        this.invocationsPerIteration = invocationsPerIteration;
        return this;
    }

    @Override
    public RandomSearchCTestConfiguration createTestConfigurations(Class<?> testClass) {
        return new RandomSearchCTestConfiguration(testClass, iterations, threads, actorsPerThread, actorsBefore, actorsAfter,
            executionGenerator, verifier, guarantee, maxRepetitions, invocationsPerIteration,
            requireStateEquivalenceImplementationCheck, minimizeFailedScenario,
            chooseSequentialSpecification(sequentialSpecification, testClass));
    }
}
