/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.strategy.modelchecking;

import org.jetbrains.lincheck.Lincheck;
import org.jetbrains.lincheck.LincheckSettings;
import org.jetbrains.lincheck.LincheckSettings.LoopDetection;
import org.jetbrains.lincheck.LincheckSettings.ScenarioGeneration;
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy.ModelChecking;
import org.jetbrains.lincheck.LincheckSettings.MemoryModel;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.scenario.RandomExecutionGenerator;
import org.jetbrains.lincheck.datastructures.verifier.LinearizabilityVerifier;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.jetbrains.lincheck.datastructures.ManagedStrategyGuaranteeKt.forClasses;


public class ModelCheckingSettingsTest {
    private final AtomicInteger i = new AtomicInteger();

    @Operation()
    public int incAndGet() {
        return i.incrementAndGet();
    }

    @Test
    public void test() {
        LincheckSettings settings = new LincheckSettings()
            .strategy(new ModelChecking()
                .enablePartialOrderReduction()
                .enablePartialOrderReduction(false)
                .memoryModel(MemoryModel.SequentialConsistency)
                .checkObstructionFreedom(true)
                .addGuarantee(forClasses("java.util.WeakHashMap").allMethods().ignore())
                .loopDetection(new LoopDetection().loopBound(30))
            )
            .scenarioGeneration(new ScenarioGeneration()
                .randomScenarios(10)
                .invocationsPerScenario(200)
                .executionGenerator(RandomExecutionGenerator.class)
                .verifier(LinearizabilityVerifier.class)
                .threads(2)
                .operationsPerThread(3)
                .minimizeFailedScenario(false)
            );
        Lincheck.runConcurrentTest(settings, ModelCheckingSettingsTest.class);
    }
}
