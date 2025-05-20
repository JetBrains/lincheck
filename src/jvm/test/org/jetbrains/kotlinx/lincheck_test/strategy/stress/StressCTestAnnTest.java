/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.stress;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.strategy.stress.*;
import org.jetbrains.kotlinx.lincheck.verifier.*;
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*;
import org.junit.*;

import java.util.concurrent.atomic.*;

@StressCTest(threads = 3, actorsPerThread = 3, iterations = 10, invocationsPerIteration = 5,
        generator = RandomExecutionGenerator.class, verifier = LinearizabilityVerifier.class)
public class StressCTestAnnTest extends VerifierState {
    private final AtomicInteger i = new AtomicInteger();

    @Operation()
    public int incAndGet() {
        return i.incrementAndGet();
    }

    @Test
    public void test() {
        LinChecker.check(StressCTestAnnTest.class);
    }

    @NotNull
    @Override
    protected Object extractState() {
        return i.get();
    }
}
