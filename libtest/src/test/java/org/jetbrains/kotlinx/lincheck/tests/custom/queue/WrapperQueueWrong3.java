package org.jetbrains.kotlinx.lincheck.tests.custom.queue;

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
import org.junit.Test;
import tests.custom.queue.Queue;
import tests.custom.queue.QueueEmptyException;
import tests.custom.queue.QueueWrong3;

@StressCTest
public class WrapperQueueWrong3 {
    private Queue queue = new QueueWrong3(10);

    @Operation(handleExceptionsAsResult = QueueEmptyException.class)
    public void put(@Param(gen = IntGen.class)int args) throws Exception {
        queue.put(args);
    }

    @Operation(handleExceptionsAsResult = QueueEmptyException.class)
    public int get() throws Exception {
        return queue.get();
    }

    @Test(expected = AssertionError.class)
    public void test() throws Exception {
        LinChecker.check(WrapperQueueWrong2.class);
    }
}
