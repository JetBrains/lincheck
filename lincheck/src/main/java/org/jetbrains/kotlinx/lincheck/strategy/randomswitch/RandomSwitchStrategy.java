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

import org.jetbrains.kotlinx.lincheck.ErrorType;
import org.jetbrains.kotlinx.lincheck.Reporter;
import org.jetbrains.kotlinx.lincheck.TestReport;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario;
import org.jetbrains.kotlinx.lincheck.strategy.ManagedStrategy;
import org.jetbrains.kotlinx.lincheck.verifier.Verifier;

/**
 * This managed strategy switches current thread to a random one with the specified probability.
 * In addition it tries to avoid both communication and resource deadlocks and to check for livelocks.
 * <p>
 * TODO: not developed yet, dummy implementation only
 */
public class RandomSwitchStrategy extends ManagedStrategy {
    private final int invocations;

    public RandomSwitchStrategy(Class<?> testClass, ExecutionScenario scenario,
        Verifier verifier, RandomSwitchCTestConfiguration testCfg, Reporter reporter)
    {
        super(testClass, scenario, verifier, reporter);
        this.invocations = testCfg.invocationsPerIteration;
    }

    @Override
    protected TestReport runImpl() throws Exception {
        for (int i = 0; i < invocations; i++)
            if (!verifyResults(runInvocation())) {
                report.setErrorInvocation(i + 1);
                return report;
            }

        return new TestReport(ErrorType.NO_ERROR);
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
