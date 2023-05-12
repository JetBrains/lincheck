/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.test.strategy.stress;

import org.jetbrains.lincheck.*;
import org.jetbrains.lincheck.annotations.*;
import org.jetbrains.lincheck.execution.*;
import org.jetbrains.lincheck.strategy.stress.*;
import org.jetbrains.lincheck.verifier.linearizability.*;
import org.junit.*;

import java.util.concurrent.atomic.*;


public class StressOptionsTest {
    private final AtomicInteger i = new AtomicInteger();

    @Operation()
    public int incAndGet() {
        return i.incrementAndGet();
    }

    @Test
    public void test() {
        StressOptions opts = new StressOptions()
            .iterations(10)
            .invocationsPerIteration(200)
            .executionGenerator(RandomExecutionGenerator.class)
            .verifier(LinearizabilityVerifier.class)
            .threads(2)
            .actorsPerThread(3)
            .logLevel(LoggingLevel.WARN)
            .minimizeFailedScenario(false);
        LinChecker.check(StressOptionsTest.class, opts);
    }
}