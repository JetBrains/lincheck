/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.api;

import org.jetbrains.lincheck.LincheckSettings.MemoryModel;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.jetbrains.lincheck.datastructures.verifier.LinearizabilityVerifier;
import org.jetbrains.lincheck.scenario.RandomExecutionGenerator;
import org.jetbrains.lincheck.util.LoggingLevel;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.jetbrains.lincheck.datastructures.ManagedStrategyGuaranteeKt.forClasses;

@SuppressWarnings("deprecation")
public class LegacyOptionsJavaTest {
    private final AtomicInteger counter = new AtomicInteger();

    @Operation
    public int incAndGet() {
        return counter.incrementAndGet();
    }

    @Test
    public void stressOptionsFluentApi() {
        StressOptions options = new StressOptions()
            .iterations(10)
            .invocationsPerIteration(200)
            .executionGenerator(RandomExecutionGenerator.class)
            .verifier(LinearizabilityVerifier.class)
            .threads(2)
            .actorsPerThread(3)
            .logLevel(LoggingLevel.WARN)
            .minimizeFailedScenario(false);
        options.check(LegacyOptionsJavaTest.class);
    }

    @Test
    public void modelCheckingOptionsFluentApi() {
        ModelCheckingOptions options = new ModelCheckingOptions()
            .enablePartialOrderReduction()
            .enablePartialOrderReduction(false)
            .memoryModel(MemoryModel.SequentialConsistency)
            .iterations(10)
            .invocationsPerIteration(200)
            .executionGenerator(RandomExecutionGenerator.class)
            .verifier(LinearizabilityVerifier.class)
            .threads(2)
            .actorsPerThread(3)
            .checkObstructionFreedom(true)
            .loopBound(30)
            .logLevel(LoggingLevel.WARN)
            .addGuarantee(forClasses("java.util.WeakHashMap").allMethods().ignore())
            .minimizeFailedScenario(false);
        options.check(LegacyOptionsJavaTest.class);
    }
}
