package com.devexperts.dxlab.lincheck.stress;

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

import com.devexperts.dxlab.lincheck.CTestConfiguration;
import com.devexperts.dxlab.lincheck.TestThreadConfiguration;
import com.devexperts.dxlab.lincheck.execution.ExecutionGenerator;
import com.devexperts.dxlab.lincheck.verifier.Verifier;

import java.util.List;

/**
 * Configuration for stress strategy.
 */
public class StressCTestConfiguration extends CTestConfiguration {
    public static final int DEFAULT_INVOCATIONS = 1_000;

    public final int invocationsPerIteration;

    public StressCTestConfiguration(int iterations, List<TestThreadConfiguration> threadConfigurations,
        Class<? extends ExecutionGenerator> generatorClass, Class<? extends Verifier> verifierClass,
        int invocationsPerIteration)
    {
        super(iterations, threadConfigurations, generatorClass, verifierClass);
        this.invocationsPerIteration = invocationsPerIteration;
    }
}
