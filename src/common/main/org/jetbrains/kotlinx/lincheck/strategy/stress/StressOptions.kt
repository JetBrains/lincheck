/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */
package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.*

/**
 * Options for [stress][StressStrategy] strategy.
 */
open class StressOptions : Options<StressOptions, StressCTestConfiguration>() {
    protected var invocationsPerIteration = StressCTestConfiguration.DEFAULT_INVOCATIONS
    protected var initThreadFunction: (() -> Unit)? = null
    protected var finishThreadFunction: (() -> Unit)? = null

    /**
     * Run each test scenario the specified number of times.
     */
    fun invocationsPerIteration(invocations: Int): StressOptions = apply {
        invocationsPerIteration = invocations
    }

    /**
     * Setup init function that will be invoked once in every worker thread before operations.
     */
    fun initThreadFunction(function: () -> Unit): StressOptions = apply {
        initThreadFunction = function
    }

    /**
     * Setup finish function that will be invoked once in every worker thread after operations.
     */
    fun finishThreadFunction(function: () -> Unit): StressOptions = apply {
        finishThreadFunction = function
    }

    override fun createTestConfigurations(testClass: TestClass): StressCTestConfiguration {
        return StressCTestConfiguration(testClass, iterations, threads, actorsPerThread, actorsBefore, actorsAfter, executionGeneratorGenerator,
                verifierGenerator, invocationsPerIteration, requireStateEquivalenceImplementationCheck, minimizeFailedScenario,
                chooseSequentialSpecification(sequentialSpecification, testClass), timeoutMs, customScenarios, initThreadFunction, finishThreadFunction)
    }
}
