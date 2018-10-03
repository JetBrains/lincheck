package com.devexperts.dxlab.lincheck.test;

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

import com.devexperts.dxlab.lincheck.LinChecker;
import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.strategy.stress.StressCTest;
import org.junit.Test;

@StressCTest(threads = 3, iterations = 100, invocationsPerIteration = 10)
public class RunOnceTest {
    private A a = new A();;

    @Operation(runOnce = true)
    public void a() {
        a.a();
    }

    @Operation(runOnce = true)
    public void b() {
        a.b();
    }

    @Test
    public void test() {
        LinChecker.check(RunOnceTest.class);
    }

    class A {
        private boolean a, b;
        synchronized void a() {
            if (a)
                throw new AssertionError();
            a = true;
        }

        synchronized void b() {
            if (b)
                throw new AssertionError();
            b = true;
        }
    }
}
