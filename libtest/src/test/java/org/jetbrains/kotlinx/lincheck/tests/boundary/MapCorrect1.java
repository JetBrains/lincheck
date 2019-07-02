package org.jetbrains.kotlinx.lincheck.tests.boundary;

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
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.junit.Test;

import java.util.Map;

@StressCTest
@Param(name = "key", gen = IntGen.class)
@Param(name = "value", gen = IntGen.class)
public class MapCorrect1 {
    private Map<Integer, Integer> map = new NonBlockingHashMap<>();

    @Operation
    public Integer put(Integer key, Integer value) {
        return map.put(key, value);
    }

    @Operation
    public Integer get(Integer key) {
        return map.get(key);
    }

    @Operation(handleExceptionsAsResult = NullPointerException.class)
    public int putIfAbsent(int key, int value) {
        return map.putIfAbsent(key, value);
    }

    @Test
    public void test() {
        LinChecker.check(MapCorrect1.class);
    }
}
