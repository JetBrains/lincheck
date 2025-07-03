/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking;

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.util.LoggingLevel;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.kotlinx.lincheck.execution.RandomExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.verifier.LinearizabilityVerifier;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.jetbrains.lincheck.datastructures.ManagedStrategyGuaranteeKt.forClasses;


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
