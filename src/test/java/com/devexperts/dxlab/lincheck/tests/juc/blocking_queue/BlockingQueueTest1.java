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

package com.devexperts.dxlab.lincheck.tests.juc.blocking_queue;

import com.devexperts.dxlab.lincheck.Checker;
import com.devexperts.dxlab.lincheck.annotations.Operation;
import com.devexperts.dxlab.lincheck.annotations.CTest;
import com.devexperts.dxlab.lincheck.annotations.ReadOnly;
import com.devexperts.dxlab.lincheck.annotations.Reload;
import com.devexperts.dxlab.lincheck.util.Result;
import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.assertTrue;


@CTest(iter = 300, actorsPerThread = {"1:3", "1:3"})
@CTest(iter = 300, actorsPerThread = {"1:3", "1:3", "1:3"})
public class BlockingQueueTest1 {
    public BlockingQueue<Integer> q;

    @Reload
    public void reload() {
        q = new ArrayBlockingQueue<Integer>(10);
    }

    @Operation(args = {"1:10"})
    public void add(Result res, Object[] args) throws Exception {
        Integer value = (Integer) args[0];

        res.setValue(q.add(value));
    }

    @ReadOnly
    @Operation(args = {})
    public void element(Result res, Object[] args)  throws Exception  {
        res.setValue(q.element());
    }

    @Operation(args = {})
    public void remove(Result res, Object[] args) throws Exception {
        res.setValue(q.remove());
    }

    @Operation(args = {})
    public void poll(Result res, Object[] args) throws Exception {
        res.setValue(q.poll());
    }


    @Test
    public void test() throws Exception {
        assertTrue(Checker.check(new BlockingQueueTest1()));
    }
}

