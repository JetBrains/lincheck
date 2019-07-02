package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability;

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
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;
import org.junit.Test;

import java.util.concurrent.ConcurrentHashMap;

@Param(name = "key", gen = IntGen.class, conf = "1:5")
@StressCTest(actorsPerThread = 50)
public class ConcurrentHashMapTest {
    private ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();

    @Operation
    public Integer put(@Param(name = "key") Integer key, Integer value) {
        return map.put(key, value);
    }

    @Operation
    public Integer get(@Param(name = "key") Integer key) {
        return map.get(key);
    }

    @Test
    public void test() {
        LinChecker.check(ConcurrentHashMapTest.class);
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConcurrentHashMapTest that = (ConcurrentHashMapTest) o;
        return map.equals(that.map);
    }

    @Override
    public int hashCode() {
        return Objects.hash(map);
    }
}
