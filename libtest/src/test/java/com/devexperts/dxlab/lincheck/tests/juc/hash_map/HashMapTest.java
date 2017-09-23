/*
 *  Lincheck - Linearizability checker
 *  Copyright (C) 2015 Devexperts LLC
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.devexperts.dxlab.lincheck.tests.juc.hash_map;

/*
 * #%L
 * libtest
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
import com.devexperts.dxlab.lincheck.stress.StressCTest;
import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.annotations.Param;
import com.devexperts.dxlab.lincheck.annotations.Reset;
import com.devexperts.dxlab.lincheck.paramgen.IntGen;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

@StressCTest(iterations = 300, actorsPerThread = {"1:3", "1:3"})
@StressCTest(iterations = 300, actorsPerThread = {"1:3", "1:3", "1:3"})
@Param(name = "key", gen = IntGen.class)
@Param(name = "value", gen = IntGen.class)
public class HashMapTest {
    private Map<Integer, Integer> m;

    @Reset
    public void reload() {
        m = new HashMap<>();
    }

    @Operation(params = {"key", "value"})
    public Integer put(Integer key, Integer value) {
        return m.put(key, value);
    }

    @Operation
    public Integer get(@Param(name = "key") Integer key) {
        return m.get(key);
    }

    @Test(expected = AssertionError.class)
    public void test() throws Exception {
        LinChecker.check(HashMapTest.class);
    }
}

