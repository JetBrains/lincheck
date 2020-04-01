package org.jetbrains.kotlinx.lincheck.strategy;

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

import org.jetbrains.kotlinx.lincheck.CTestConfiguration;
import org.jetbrains.kotlinx.lincheck.Reporter;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario;
import org.jetbrains.kotlinx.lincheck.strategy.randomswitch.RandomSwitchCTestConfiguration;
import org.jetbrains.kotlinx.lincheck.strategy.randomswitch.RandomSwitchStrategy;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTestConfiguration;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressStrategy;
import org.jetbrains.kotlinx.lincheck.verifier.Verifier;
import org.objectweb.asm.ClassVisitor;

import static org.jetbrains.kotlinx.lincheck.ReporterKt.appendIncorrectResults;

/**
 * Implementation of this class describes how to run the generated execution.
 * <p>
 * Note that strategy could run execution several times. For strategy creating
 * {@link #createStrategy} method is used. It is impossible to add new strategy
 * without any code change.
 */
public abstract class Strategy {
    protected final ExecutionScenario scenario;
    protected final Reporter reporter;
    private final Verifier verifier;

    protected Strategy(ExecutionScenario scenario, Verifier verifier, Reporter reporter) {
        this.scenario = scenario;
        this.verifier = verifier;
        this.reporter = reporter;
    }

    protected void verifyResults(ExecutionResult results) {
        if (!verifier.verifyResults(scenario, results)) {
            StringBuilder msgBuilder = new StringBuilder("Invalid interleaving found:\n");
            appendIncorrectResults(msgBuilder, scenario, results);
            throw new AssertionError(msgBuilder.toString());
        }
    }

    public ClassVisitor createTransformer(ClassVisitor cv) {
        throw new UnsupportedOperationException(getClass() + " runner does not transform classes");
    }

    public boolean needsTransformation() {
        return false;
    }

    /**
     * Creates {@link Strategy} based on {@code testCfg} type.
     */
    public static Strategy createStrategy(CTestConfiguration testCfg, Class<?> testClass,
        ExecutionScenario scenario, Verifier verifier, Reporter reporter)
    {
        if (testCfg instanceof StressCTestConfiguration) {
            return new StressStrategy(testClass, scenario, verifier,
                (StressCTestConfiguration) testCfg, reporter);
        } else if (testCfg instanceof RandomSwitchCTestConfiguration) {
            return new RandomSwitchStrategy(testClass, scenario, verifier,
                (RandomSwitchCTestConfiguration) testCfg, reporter);
        }
        throw new IllegalArgumentException("Unknown strategy configuration type: " + testCfg.getClass());
    }

    public abstract void run() throws Exception;
}
