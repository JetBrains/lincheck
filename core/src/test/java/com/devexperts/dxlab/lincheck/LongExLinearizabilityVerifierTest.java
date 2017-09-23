package com.devexperts.dxlab.lincheck;

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

import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.annotations.Param;
import com.devexperts.dxlab.lincheck.annotations.Reset;
import com.devexperts.dxlab.lincheck.paramgen.IntGen;
import com.devexperts.dxlab.lincheck.stress.StressCTest;
import com.devexperts.dxlab.lincheck.verifier.LongExLinearizabilityVerifier;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@StressCTest(iterations = 1, invocationsPerIteration = 1,
    actorsPerThread = {"50:50", "50:50"},
    verifier = LongExLinearizabilityVerifier.class)
@Param(name = "key", gen = IntGen.class, conf = "1:20")
@Param(name = "value", gen = IntGen.class)
public class LongExLinearizabilityVerifierTest {
    private Map<Integer, Integer> m;

    @Reset
    public void reload() {
        m = new ConcurrentHashMap<>();
    }

    @Operation(params = {"key", "value"})
    public Integer put(Integer key, Integer value) {
        return m.put(key, value);
    }

    @Operation
    public Integer get(@Param(name = "key") Integer key) {
        return m.get(key);
    }

    @Test
    public void test() throws Exception {
        LinChecker.check(LongExLinearizabilityVerifierTest.class);
    }
}