package org.jetbrains.kotlinx.lincheck.strategy.randomswitch;

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

import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.runner.*;
import org.jetbrains.kotlinx.lincheck.strategy.*;
import org.jetbrains.kotlinx.lincheck.verifier.*;

import java.lang.reflect.*;
import java.util.*;

import static org.jetbrains.kotlinx.lincheck.strategy.LincheckFailureKt.toLincheckFailure;


/**
 * This managed strategy switches the current thread to a random one with the specified probability.
 * In addition it tries to avoid both communication and resource deadlocks and to check for livelocks.
 * <p>
 * TODO: not developed yet, dummy implementation only
 */
public class RandomSwitchStrategy extends ManagedStrategy {
    private final int invocations;
    private final Verifier verifier;

    public RandomSwitchStrategy(RandomSwitchCTestConfiguration testCfg, Class<?> testClass,
                                ExecutionScenario scenario, List<Method> validationFunctions,
                                Verifier verifier) {
        super(testClass, scenario, validationFunctions);
        this.invocations = testCfg.invocationsPerIteration;
        this.verifier = verifier;
    }

    @Override
    protected LincheckFailure runImpl() {
        for (int i = 1; i < invocations; i++) {
            InvocationResult ir = runInvocation();
            if (ir instanceof CompletedInvocationResult) {
                ExecutionResult results = ((CompletedInvocationResult) ir).getResults();
                if (!verifier.verifyResults(getScenario(), results))
                    return new IncorrectResultsFailure(getScenario(), results);
            } else {
                return toLincheckFailure(ir, getScenario());
            }
        }
        return null;
    }

    @Override
    public void onStart(int iThread) {
        super.onStart(iThread);
    }

    @Override
    public void beforeSharedVariableRead(int iThread, int codeLocation) {
        Thread.yield();
    }

    @Override
    public void beforeSharedVariableWrite(int iThread, int codeLocation) {
        Thread.yield();
    }
}
