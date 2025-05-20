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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.kotlinx.lincheck.execution.RandomExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest;
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

@ModelCheckingCTest(threads = 2, actorsPerThread = 3, iterations = 10, invocationsPerIteration = 5,
        generator = RandomExecutionGenerator.class, verifier = LinearizabilityVerifier.class, checkObstructionFreedom = true)
public class ModelCheckingCTestAnnTest extends VerifierState {
    private final AtomicInteger i = new AtomicInteger();

    @Operation()
    public int incAndGet() {
        return i.incrementAndGet();
    }

    @Test
    public void test() {
        LinChecker.check(ModelCheckingCTestAnnTest.class);
    }

    @NotNull
    @Override
    protected Object extractState() {
        return i.get();
    }
}
