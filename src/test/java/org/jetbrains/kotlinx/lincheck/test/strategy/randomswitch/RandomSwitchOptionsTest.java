package org.jetbrains.kotlinx.lincheck.test.strategy.randomswitch;

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

import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.annotations.*;
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.strategy.managed.randomswitch.*;
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*;
import org.junit.*;

import java.util.concurrent.atomic.*;

public class RandomSwitchOptionsTest {
    private final AtomicInteger i = new AtomicInteger();

    @Operation()
    public int incAndGet() {
        return i.incrementAndGet();
    }

    @Test
    public void test() {
        RandomSwitchOptions opts = new RandomSwitchOptions()
            .iterations(10)
            .invocationsPerIteration(200)
            .executionGenerator(RandomExecutionGenerator.class)
            .threads(2)
            .actorsPerThread(4)
            .verifier(LinearizabilityVerifier.class)
            .requireStateEquivalenceImplCheck(false)
            .minimizeFailedScenario(false);
        LinChecker.check(RandomSwitchOptionsTest.class, opts);
    }
}