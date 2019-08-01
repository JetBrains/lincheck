package org.jetbrains.kotlinx.lincheck.tests.custom.counter;

/*
 * #%L
 * libtest
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
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;
import org.junit.Test;
import tests.custom.counter.CounterGet;

@StressCTest
public class CounterGetTest extends VerifierState {
    private CounterGet counter = new CounterGet();;

    @Operation
    public int incAndGet() {
        return counter.incrementAndGet();
    }

    @Operation
    public int get() {
        return counter.get();
    }

    @Test
    public void test() {
        LinChecker.check(CounterGetTest.class);
    }

    @Override
    protected Object extractState() {
        return counter.get();
    }
}
