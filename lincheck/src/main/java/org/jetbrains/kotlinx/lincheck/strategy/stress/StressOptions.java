package org.jetbrains.kotlinx.lincheck.strategy.stress;

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

import org.jetbrains.kotlinx.lincheck.Options;

/**
 * Options for {@link StressStrategy stress} strategy.
 */
public class StressOptions extends Options<StressOptions, StressCTestConfiguration> {
    protected int invocationsPerIteration = StressCTestConfiguration.DEFAULT_INVOCATIONS;
    protected boolean addWaits = true;

    /**
     * Run each test scenario {@code invocations} times.
     */
    public StressOptions invocationsPerIteration(int invocations) {
        this.invocationsPerIteration = invocations;
        return this;
    }

    /**
     * Set this to {@code false} to disable random waits between operations, enabled by default.
     */
    public StressOptions addWaits(boolean value) {
        addWaits = value;
        return this;
    }

    @Override
    public StressCTestConfiguration createTestConfigurations() {
        return new StressCTestConfiguration(iterations, threads, actorsPerThread, actorsBefore, actorsAfter,
            executionGenerator, verifier, invocationsPerIteration, addWaits, requireStateEquivalenceImplementationCheck);
    }
}
