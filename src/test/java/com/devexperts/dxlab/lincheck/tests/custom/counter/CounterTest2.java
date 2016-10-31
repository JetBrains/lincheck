package com.devexperts.dxlab.lincheck.tests.custom.counter;

/*
 * #%L
 * lin-check
 * %%
 * Copyright (C) 2015 - 2016 Devexperts, LLC
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

import com.devexperts.dxlab.lincheck.Checker;
import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.annotations.CTest;
import com.devexperts.dxlab.lincheck.annotations.Reload;
import com.devexperts.dxlab.lincheck.util.Result;
import org.junit.Test;

import static junit.framework.TestCase.assertTrue;

@CTest(iter = 300, actorsPerThread = {"1:3", "1:3"})
@CTest(iter = 300, actorsPerThread = {"1:3", "1:3", "1:3"})
public class CounterTest2 {
    public Counter counter;

    @Reload
    public void reload() {
        counter = new CounterCorrect2();
    }

    @Operation(args = {})
    public void incAndGet(Result res, Object[] args) {
        Integer v = counter.incrementAndGet();
        res.setValue(v);
    }

    @Test
    public void test() throws Exception {
        Checker checker = new Checker();
        assertTrue(checker.checkAnnotated(new CounterTest2()));
    }
}
