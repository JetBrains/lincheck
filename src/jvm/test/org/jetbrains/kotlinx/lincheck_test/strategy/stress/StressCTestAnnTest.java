/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.stress;

import org.jetbrains.annotations.*;
import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
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
