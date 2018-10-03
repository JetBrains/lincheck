package com.devexperts.dxlab.lincheck.test.verifier.quiescent;

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
import com.devexperts.dxlab.lincheck.annotations.OpGroupConfig;
import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.annotations.Param;
import com.devexperts.dxlab.lincheck.paramgen.IntGen;
import com.devexperts.dxlab.lincheck.strategy.stress.StressCTest;
import com.devexperts.dxlab.lincheck.verifier.quiescent.QuiescentConsistencyVerifier;
import com.devexperts.dxlab.lincheck.verifier.quiescent.QuiescentConsistent;
import org.junit.Test;

@StressCTest(verifier = QuiescentConsistencyVerifier.class)
@OpGroupConfig(name = "consumer", nonParallel = true)
public class LockFreeMPSCQueueTest {
    private LockFreeMPSCQueue<Integer> q = new LockFreeMPSCQueue<>();

    @Operation(group = "consumer")
    @QuiescentConsistent
    public Integer removeFirstOrNull() {
        return q.removeFirstOrNull();
    }

    @Operation
    public boolean addLast(@Param(gen = IntGen.class) Integer val) {
        return q.addLast(val);
    }

    @Operation(runOnce = true)
    @QuiescentConsistent
    public void close() {
        q.close();
    }

    @Test
    public void test() {
        LinChecker.check(LockFreeMPSCQueueTest.class);
    }
}