/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test;

import org.jetbrains.lincheck.Lincheck;
import org.jetbrains.lincheck.LincheckSettings;
import org.jetbrains.lincheck.LincheckSettings.ScenarioGeneration;
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy.Stress;
import org.jetbrains.lincheck.datastructures.Operation;
import org.junit.Test;

public class RunOnceTest {
    private A a = new A();

    @Operation(runOnce = true)
    public void a() {
        a.a();
    }

    @Operation(runOnce = true)
    public void b() {
        a.b();
    }

    @Test
    public void test() {
        LincheckSettings settings = new LincheckSettings()
            .strategy(new Stress())
            .scenarioGeneration(new ScenarioGeneration()
                .threads(3)
                .randomScenarios(10)
                .invocationsPerScenario(10)
            );
        Lincheck.runConcurrentTest(settings, RunOnceTest.class);
    }

    class A {
        private boolean a, b;
        synchronized void a() {
            if (a)
                throw new AssertionError();
            a = true;
        }

        synchronized void b() {
            if (b)
                throw new AssertionError();
            b = true;
        }
    }
}