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
import org.cliffc.high_scale_lib.NonBlockingHashSet;
import org.junit.Test;

@StressCTest
@Param(name = "key", gen = IntGen.class)
public class SetCorrect1 {
    private NonBlockingHashSet<Integer> q = new NonBlockingHashSet<>();

    @Operation(params = {"key"})
    public boolean add(int key) {
        return q.add(key);
    }

    @Operation(params = {"key"})
    public boolean remove(int key) {
        return q.remove(key);
    }

    @Test
    public void test() {
        LinChecker.check(SetCorrect1.class);
    }
}
