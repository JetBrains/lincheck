package com.devexperts.dxlab.lincheck.stress;

/*
 * #%L
 * core
 * %%
 * Copyright (C) 2015 - 2017 Devexperts, LLC
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

import com.devexperts.dxlab.lincheck.LinChecker;
import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.annotations.Reset;
import com.devexperts.dxlab.lincheck.execution.RandomExecutionGenerator;
import com.devexperts.dxlab.lincheck.verifier.LinearizabilityVerifier;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

@StressCTest(actorsPerThread = {"1:3", "1:3"}, iterations = 10, invocationsPerIteration = 5,
    generator = RandomExecutionGenerator.class, verifier = LinearizabilityVerifier.class)
public class StressCTestAnnTest {
    private AtomicInteger i;

    @Reset
    public void reload() {
        i = new AtomicInteger();
    }

    @Operation()
    public int incAndGet() {
        return i.incrementAndGet();
    }

    @Test
    public void test() {
        LinChecker.check(StressCTestAnnTest.class);
    }
}
