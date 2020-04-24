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
import org.jetbrains.kotlinx.lincheck.execution.*;
import org.jetbrains.kotlinx.lincheck.runner.*;
import org.jetbrains.kotlinx.lincheck.strategy.*;
import org.junit.*;

import java.util.*;

import static java.util.Arrays.*;
import static java.util.Collections.*;

public class TestThreadExecutionHelperTest {
    private Runner runner;

    @Before
    public void setUp() {
        ExecutionScenario scenario = new ExecutionScenario(emptyList(), emptyList(), emptyList());
        Strategy strategy = new Strategy(scenario) {
            @Override
            public LincheckFailure run() {
                throw new UnsupportedOperationException();
            }
        };
        runner = new Runner(strategy, ArrayDeque.class, emptyList()) {
            @Override
            public InvocationResult run() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    public void testBase() throws Exception {
        TestThreadExecution ex = TestThreadExecutionGenerator.create(runner, 0,
            asList(
                new Actor(Queue.class.getMethod("add", Object.class), asList(1), emptyList()),
                new Actor(Queue.class.getMethod("add", Object.class), asList(2), emptyList()),
                new Actor(Queue.class.getMethod("remove"), emptyList(), emptyList()),
                new Actor(Queue.class.getMethod("element"), emptyList(), emptyList()),
                new Actor(Queue.class.getMethod("peek"), emptyList(), emptyList())
            ), emptyList(), false, false);
        ex.testInstance = new ArrayDeque<>();
        ex.results = new Result[5];
        ex.run();
        Assert.assertArrayEquals(new Result[]{
            new ValueResult(true),
            new ValueResult(true),
            new ValueResult(1),
            new ValueResult(2),
            new ValueResult(2)
        }, ex.results);
    }

    @Test(expected = NoSuchElementException.class)
    public void testGlobalException() throws Exception {
        TestThreadExecution ex = TestThreadExecutionGenerator.create(runner, 0,
            asList(
                new Actor(Queue.class.getMethod("add", Object.class), asList(1), emptyList()),
                new Actor(Queue.class.getMethod("remove"), emptyList(), emptyList()),
                new Actor(Queue.class.getMethod("remove"), emptyList(), emptyList()),
                new Actor(Queue.class.getMethod("add", Object.class), asList(2), emptyList())
            ), emptyList(), false, false);
        ex.testInstance = new ArrayDeque<>();
        ex.results = new Result[4];
        ex.run();
    }

    @Test
    public void testActorExceptionHandling() throws Exception {
        TestThreadExecution ex = TestThreadExecutionGenerator.create(runner, 0,
            asList(
                new Actor(ArrayDeque.class.getMethod("addLast", Object.class), asList(1), emptyList()),
                new Actor(Queue.class.getMethod("remove"), emptyList(), emptyList()),
                new Actor(Queue.class.getMethod("remove"), emptyList(), asList(NoSuchElementException.class)),
                new Actor(Queue.class.getMethod("remove"), emptyList(), asList(Exception.class, NoSuchElementException.class))
            ), emptyList(), false, false);
        ex.testInstance = new ArrayDeque<>();
        ex.results = new Result[4];
        ex.clocks = new int[4][1];
        ex.allThreadExecutions = (TestThreadExecution[]) asList(ex).toArray();
        ex.run();
        Assert.assertArrayEquals(new Result[]{
            VoidResult.INSTANCE,
            new ValueResult(1),
            ExceptionResult.Companion.create(NoSuchElementException.class),
            ExceptionResult.Companion.create(NoSuchElementException.class)
        }, ex.results);
    }

    @Test
    public void testWaits() throws Exception {
        TestThreadExecution ex = TestThreadExecutionGenerator.create(runner, 0,
            asList(
                new Actor(Queue.class.getMethod("add", Object.class), asList(1), emptyList()),
                new Actor(Queue.class.getMethod("remove"), emptyList(), emptyList()),
                new Actor(Queue.class.getMethod("remove"), emptyList(), asList(NoSuchElementException.class))
            ), emptyList(), true, false);
        ex.testInstance = new ArrayDeque<>();
        ex.results = new Result[3];
        ex.waits = new int[]{15, 100};
        ex.run();
        Assert.assertArrayEquals(new Result[]{
            new ValueResult(true),
            new ValueResult(1),
            ExceptionResult.Companion.create(NoSuchElementException.class)
        }, ex.results);
    }
}