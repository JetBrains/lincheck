package com.devexperts.dxlab.lincheck.tests.custom.queue;

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

import static org.junit.Assert.assertTrue;

@CTest(iter = 300, actorsPerThread = {"1:3", "1:3"})
@CTest(iter = 300, actorsPerThread = {"1:3", "1:3", "1:3"})
public class QueueTest1 {
    public Queue queue;

    @Reload
    public void reload() {
        queue = new QueueSynchronized(10);
    }

    @Operation(args = {"1:10"})
    public void put(Result res, Object[] args) throws Exception {
        Integer x = (Integer) args[0];
        queue.put(x);
        res.setVoid();
    }

    @Operation(args = {})
    public void get(Result res, Object[] args) throws Exception {
        Integer value = queue.get();
        res.setValue(value);
    }

    @Test
    public void test() throws Exception {
        Checker checker = new Checker();
        assertTrue(checker.checkAnnotated(new QueueTest1()));
    }
}
