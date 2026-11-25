/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.transformation;

import org.jetbrains.lincheck.Lincheck;
import org.jetbrains.lincheck.LincheckSettings;
import org.jetbrains.lincheck.LincheckSettings.ScenarioGeneration;
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy.ModelChecking;
import org.jetbrains.lincheck.LincheckAssertionError;
import org.jetbrains.lincheck.datastructures.Operation;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static org.junit.Assert.fail;

/**
 * This test checks that indirect writes via {@link AtomicIntegerFieldUpdater}-s
 * are processed as normal writes.
 */
public class WritesViaAtomicFieldUpdaterTest {
    private static final AtomicReferenceFieldUpdater<WritesViaAtomicFieldUpdaterTest, VariableHolder> afu = AtomicReferenceFieldUpdater.newUpdater(WritesViaAtomicFieldUpdaterTest.class, VariableHolder.class, "holder");

    private volatile VariableHolder holder = null;

    @Operation
    public int operation() {
        VariableHolder h = new VariableHolder();
        afu.updateAndGet(this, cur -> h); // initialize holder via CAS
        return holder.variable++;
    }

    @Test
    public void test() {
        try {
            LincheckSettings settings = new LincheckSettings()
                .strategy(new ModelChecking())
                .scenarioGeneration(new ScenarioGeneration()
                    .operationsPerThread(1)
                    .operationsBefore(0)
                    .operationsAfter(0)
                    .randomScenarios(1)
                );
            Lincheck.runConcurrentTest(settings, this.getClass());
        } catch (LincheckAssertionError e) {
            // the test should fail
            return;
        }
        fail("Written VariableHolder was wrongly treated as local object");
    }

    private static class VariableHolder {
        int variable = 0;
    }
}
