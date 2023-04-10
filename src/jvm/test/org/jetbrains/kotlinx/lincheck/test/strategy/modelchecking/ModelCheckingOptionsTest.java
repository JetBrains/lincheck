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
package org.jetbrains.kotlinx.lincheck.test.strategy.modelchecking;

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.LoggingLevel;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.execution.RandomExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions;
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyGuaranteeKt.forClasses;


public class ModelCheckingOptionsTest {
    private final AtomicInteger i = new AtomicInteger();

    @Operation()
    public int incAndGet() {
        return i.incrementAndGet();
    }

    @Test
    public void test() {
        ModelCheckingOptions opts = new ModelCheckingOptions()
            .iterations(10)
            .invocationsPerIteration(200)
            .executionGenerator(RandomExecutionGenerator.class)
            .verifier(LinearizabilityVerifier.class)
            .threads(2)
            .actorsPerThread(3)
            .checkObstructionFreedom(true)
            .hangingDetectionThreshold(30)
            .logLevel(LoggingLevel.WARN)
            .addGuarantee(forClasses("java.util.WeakHashMap").allMethods().ignore())
            .minimizeFailedScenario(false);
        LinChecker.check(ModelCheckingOptionsTest.class, opts);
    }
}
