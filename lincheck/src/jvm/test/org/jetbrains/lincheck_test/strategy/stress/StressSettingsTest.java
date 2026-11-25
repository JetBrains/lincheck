/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.strategy.stress;

import org.jetbrains.lincheck.Lincheck;
import org.jetbrains.lincheck.LincheckSettings;
import org.jetbrains.lincheck.LincheckSettings.ScenarioGeneration;
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy.Stress;
import org.jetbrains.lincheck.scenario.*;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.verifier.LinearizabilityVerifier;
import org.junit.*;

import java.util.concurrent.atomic.*;


public class StressSettingsTest {
    private final AtomicInteger i = new AtomicInteger();

    @Operation()
    public int incAndGet() {
        return i.incrementAndGet();
    }

    @Test
    public void test() {
        LincheckSettings settings = new LincheckSettings()
            .strategy(new Stress())
            .scenarioGeneration(new ScenarioGeneration()
                .randomScenarios(10)
                .invocationsPerScenario(200)
                .executionGenerator(RandomExecutionGenerator.class)
                .verifier(LinearizabilityVerifier.class)
                .threads(2)
                .operationsPerThread(3)
                .minimizeFailedScenario(false)
            );
        Lincheck.runConcurrentTest(settings, StressSettingsTest.class);
    }
}