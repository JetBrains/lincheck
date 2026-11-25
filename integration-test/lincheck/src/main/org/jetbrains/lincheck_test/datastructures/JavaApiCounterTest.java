/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.datastructures;

import org.jetbrains.lincheck.Lincheck;
import org.jetbrains.lincheck.LincheckSettings;
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy;
import org.jetbrains.lincheck.datastructures.Operation;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class JavaApiCounterTest {
    private final Counter counter = new Counter();

    @Operation
    public int inc() {
        return counter.inc();
    }

    @Operation
    public int get() {
        return counter.get();
    }

    @Test(expected = AssertionError.class)
    public void stressTest() {
        LincheckSettings settings = new LincheckSettings().strategy(new TestingStrategy.Stress());
        Lincheck.runConcurrentTest(settings, getClass());
    }

    @Test(expected = AssertionError.class)
    public void modelCheckingTest() {
        LincheckSettings settings = new LincheckSettings().strategy(new TestingStrategy.ModelChecking());
        Lincheck.runConcurrentTest(settings, getClass());
    }

    @Test(expected = AssertionError.class)
    public void eventStructureModelCheckingTest() {
        LincheckSettings settings = new LincheckSettings()
            .strategy(new TestingStrategy.ModelChecking().enablePartialOrderReduction());
        Lincheck.runConcurrentTest(settings, getClass());
    }

    @Test
    public void configureLambdaWithJavaSetters() {
        LincheckSettings settings = new LincheckSettings();
        TestingStrategy.ModelChecking strategy = new TestingStrategy.ModelChecking();
        strategy.setLoopDetection(new LincheckSettings.LoopDetection().loopBound(100));
        settings.setStrategy(strategy);
        Lincheck.runConcurrentTest(10, settings, () -> {
            AtomicInteger value = new AtomicInteger(1);
            assertEquals(1, value.get());
        });
    }

}
