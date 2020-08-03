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
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking;

import kotlin.jvm.functions.Function0;
import org.jetbrains.kotlinx.lincheck.CTestConfiguration;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario;
import org.jetbrains.kotlinx.lincheck.strategy.managed.*;
import org.jetbrains.kotlinx.lincheck.strategy.Strategy;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*;
import org.jetbrains.kotlinx.lincheck.verifier.Verifier;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for {@link ModelCheckingStrategy random search} strategy.
 */
public class ModelCheckingCTestConfiguration extends ManagedCTestConfiguration {
    public ModelCheckingCTestConfiguration(Class<?> testClass, int iterations, int threads, int actorsPerThread, int actorsBefore,
                                           int actorsAfter, Class<? extends ExecutionGenerator> generatorClass, Class<? extends Verifier> verifierClass,
                                           boolean checkObstructionFreedom, int hangingDetectionThreshold, int invocationsPerIteration,
                                           List<ManagedGuarantee> guarantees, boolean requireStateEquivalenceCheck, boolean minimizeFailedScenario,
                                           Class<?> sequentialSpecification, long timeoutMs, boolean eliminateLocalObjects)
    {
        super(testClass, iterations, threads, actorsPerThread, actorsBefore, actorsAfter, generatorClass, verifierClass,
                checkObstructionFreedom, hangingDetectionThreshold, invocationsPerIteration, guarantees, requireStateEquivalenceCheck,
                minimizeFailedScenario, sequentialSpecification, timeoutMs, eliminateLocalObjects);
    }

    @Override
    protected Strategy createStrategy(Class<?> testClass, ExecutionScenario scenario, List<Method> validationFunctions,
                                      Method stateRepresentation, Verifier verifier) {
        return new ModelCheckingStrategy(this, testClass, scenario, validationFunctions, stateRepresentation, verifier);
    }
}