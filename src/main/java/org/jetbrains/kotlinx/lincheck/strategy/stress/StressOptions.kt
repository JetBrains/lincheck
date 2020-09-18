/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.chooseSequentialSpecification

/**
 * Options for [stress][StressStrategy] strategy.
 */
open class StressOptions : Options<StressOptions, StressCTestConfiguration>() {
    private var invocationsPerIteration = StressCTestConfiguration.DEFAULT_INVOCATIONS
    private var addWaits = true

    /**
     * Run each test scenario the specified number of times.
     */
    fun invocationsPerIteration(invocations: Int): StressOptions = apply {
        invocationsPerIteration = invocations
    }

    /**
     * Set this to `false` to disable random waits between operations, enabled by default.
     */
    fun addWaits(value: Boolean): StressOptions = apply {
        addWaits = value
    }

    override fun createTestConfigurations(testClass: Class<*>?): StressCTestConfiguration {
        return StressCTestConfiguration(testClass, iterations, threads, actorsPerThread, actorsBefore, actorsAfter, executionGenerator,
                verifier, invocationsPerIteration, addWaits, requireStateEquivalenceImplementationCheck, minimizeFailedScenario,
                chooseSequentialSpecification(sequentialSpecification, testClass!!), timeoutMs)
    }
}
