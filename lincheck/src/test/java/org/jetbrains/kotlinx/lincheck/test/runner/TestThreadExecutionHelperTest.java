package org.jetbrains.kotlinx.lincheck.test.runner;

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

import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult;
import org.jetbrains.kotlinx.lincheck.runner.Runner;
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution;
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.strategy.Strategy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Queue;

public class TestThreadExecutionHelperTest {
    private Runner runner;

    @Before
    public void setUp() {
        Strategy mockStrategy = new Strategy(null, null, null) {
            @Override
            public void run(){
                throw new UnsupportedOperationException();
            }
        };
        runner = new Runner(null, mockStrategy, ArrayDeque.class) {
            @Override
            public ExecutionResult run() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    public void testBase() throws Exception {
        TestThreadExecution ex = TestThreadExecutionGenerator.create(runner, 0,
            Arrays.asList(
                new Actor(Queue.class.getMethod("add", Object.class), Arrays.asList(1), Collections.emptyList()),
                new Actor(Queue.class.getMethod("add", Object.class), Arrays.asList(2), Collections.emptyList()),
                new Actor(Queue.class.getMethod("remove"), Collections.emptyList(), Collections.emptyList()),
                new Actor(Queue.class.getMethod("element"), Collections.emptyList(), Collections.emptyList()),
                new Actor(Queue.class.getMethod("peek"), Collections.emptyList(), Collections.emptyList())
            ), Collections.emptyList(), false, false);
        ex.testInstance = new ArrayDeque<>();
        Assert.assertArrayEquals(new Result[] {
            new ValueResult(true),
            new ValueResult(true),
            new ValueResult(1),
            new ValueResult(2),
            new ValueResult(2)
        }, ex.call());
    }

    @Test(expected = NoSuchElementException.class)
    public void testGlobalException() throws Exception {
        TestThreadExecution ex = TestThreadExecutionGenerator.create(runner, 0,
            Arrays.asList(
                new Actor(Queue.class.getMethod("add", Object.class), Arrays.asList(1), Collections.emptyList()),
                new Actor(Queue.class.getMethod("remove"), Collections.emptyList(), Collections.emptyList()),
                new Actor(Queue.class.getMethod("remove"), Collections.emptyList(), Collections.emptyList()),
                new Actor(Queue.class.getMethod("add", Object.class), Arrays.asList(2), Collections.emptyList())
            ),  Collections.emptyList(), false, false);
        ex.testInstance = new ArrayDeque<>();
        ex.call();
    }

    @Test
    public void testActorExceptionHandling() throws Exception {
        TestThreadExecution ex = TestThreadExecutionGenerator.create(runner, 0,
            Arrays.asList(
                new Actor(ArrayDeque.class.getMethod("addLast", Object.class), Arrays.asList(1), Collections.emptyList()),
                new Actor(Queue.class.getMethod("remove"), Collections.emptyList(), Collections.emptyList()),
                new Actor(Queue.class.getMethod("remove"), Collections.emptyList(), Arrays.asList(NoSuchElementException.class)),
                new Actor(Queue.class.getMethod("remove"), Collections.emptyList(), Arrays.asList(Exception.class, NoSuchElementException.class))
            ), Collections.emptyList(), false, false);
        ex.testInstance = new ArrayDeque<>();
        Assert.assertArrayEquals(new Result[] {
            VoidResult.INSTANCE,
            new ValueResult(1),
            new ExceptionResult(NoSuchElementException.class),
            new ExceptionResult(NoSuchElementException.class)
        }, ex.call());
    }

    @Test
    public void testWaits() throws Exception {
        TestThreadExecution ex = TestThreadExecutionGenerator.create(runner, 0,
            Arrays.asList(
                new Actor(Queue.class.getMethod("add", Object.class), Arrays.asList(1), Collections.emptyList()),
                new Actor(Queue.class.getMethod("remove"), Collections.emptyList(), Collections.emptyList()),
                new Actor(Queue.class.getMethod("remove"), Collections.emptyList(), Arrays.asList(NoSuchElementException.class))
            ), Collections.emptyList(), true, false);
        ex.testInstance = new ArrayDeque<>();
        ex.waits = new int[] {15, 100};
        Assert.assertArrayEquals(new Result[] {
            new ValueResult(true),
            new ValueResult(1),
            new ExceptionResult(NoSuchElementException.class)
        }, ex.call());
    }
}