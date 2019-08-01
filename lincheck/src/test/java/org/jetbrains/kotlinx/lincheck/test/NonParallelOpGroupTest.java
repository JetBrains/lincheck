package org.jetbrains.kotlinx.lincheck.test;

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
import org.jetbrains.kotlinx.lincheck.LoggingLevel;
import org.jetbrains.kotlinx.lincheck.annotations.LogLevel;
import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest;
import org.jctools.queues.atomic.SpscLinkedAtomicQueue;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

@OpGroupConfig(name = "producer", nonParallel = true)
@OpGroupConfig(name = "consumer", nonParallel = true)
@StressCTest(requireStateEquivalenceImplCheck = false)
@LogLevel(LoggingLevel.DEBUG)
public class NonParallelOpGroupTest {
    private SpscLinkedAtomicQueue<Integer> queue = new SpscLinkedAtomicQueue<>();
    private AtomicInteger i = new AtomicInteger();

    @Operation(group = "producer")
    public void offer(@Param(gen = IntGen.class) Integer x) {
        queue.offer(x);
    }

    @Operation(group = "consumer")
    public Integer poll() {
        return queue.poll();
    }

    @Operation
    public int incAndGet() {
        return i.incrementAndGet();
    }

    @Test
    public void test() {
        LinChecker.check(NonParallelOpGroupTest.class);
    }
}
