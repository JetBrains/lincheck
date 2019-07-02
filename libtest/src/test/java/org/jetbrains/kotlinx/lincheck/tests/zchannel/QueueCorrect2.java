package org.jetbrains.kotlinx.lincheck.tests.zchannel;

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
import z.channel.GenericMPMCQueue;

/**
 * http://landz.github.io/
 */
@StressCTest
public class QueueCorrect2 {
    private GenericMPMCQueue<Integer> q = new GenericMPMCQueue<>(16);

    @Operation
    public boolean offer(@Param(gen = IntGen.class) int value) {
        return q.offer(value);
    }

    @Operation
    public Integer poll() {
        return q.poll();
    }

    //    @Test TODO is it really correct?
    public void test() throws Exception {
        LinChecker.check(QueueCorrect2.class);
    }
}