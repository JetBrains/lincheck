package org.jetbrains.kotlinx.lincheck.tests.strategy.managed;

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.execution.RandomExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.strategy.randomsearch.RandomSearchCTest;
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

@RandomSearchCTest(threads = 3, actorsPerThread = 3, iterations = 5, checkObstructionFreedom = true,
    generator = RandomExecutionGenerator.class, verifier = LinearizabilityVerifier.class, invocationsPerIteration = 3)
public class RandomSearchCTestAnnTest extends VerifierState {
    private AtomicInteger i = new AtomicInteger();

    @Operation()
    public int incAndGet() {
        return i.incrementAndGet();
    }

    @Test
    public void test() {
        LinChecker.check(RandomSearchCTestAnnTest.class);
    }

    @Override
    protected Object extractState() {
        return i.get();
    }
}
