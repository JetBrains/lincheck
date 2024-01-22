/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.runner;

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
        ExecutionScenario scenario = new ExecutionScenario(emptyList(), emptyList(), emptyList(), null);
        Strategy strategy = new Strategy(scenario) {
            @Override
            public InvocationResult runInvocation() {
                throw new UnsupportedOperationException();
            }
        };
        runner = new Runner(strategy, ArrayDeque.class, null, null) {
            @Override
            public InvocationResult run() {
                throw new UnsupportedOperationException();
            }
        };
        runner.initialize();
    }

    @Test
    public void testBase() throws Exception {
        TestThreadExecution ex = TestThreadExecutionGenerator.create(runner, 0,
            asList(
                new Actor(Queue.class.getMethod("add", Object.class), asList(1)),
                new Actor(Queue.class.getMethod("add", Object.class), asList(2)),
                new Actor(Queue.class.getMethod("remove"), emptyList()),
                new Actor(Queue.class.getMethod("element"), emptyList()),
                new Actor(Queue.class.getMethod("peek"), emptyList())
            ), emptyList(), false);
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


    @Test
    public void testActorExceptionHandling() throws Exception {
        TestThreadExecution ex = TestThreadExecutionGenerator.create(runner, 0,
            asList(
                new Actor(ArrayDeque.class.getMethod("addLast", Object.class), asList(1)),
                new Actor(Queue.class.getMethod("remove"), emptyList()),
                new Actor(Queue.class.getMethod("remove"), emptyList()),
                new Actor(Queue.class.getMethod("remove"), emptyList())
            ), emptyList(), false);
        ex.testInstance = new ArrayDeque<>();
        ex.results = new Result[4];
        ex.clocks = new int[4][1];
        ex.allThreadExecutions = new TestThreadExecution[1];
        ex.allThreadExecutions[0] = ex;
        ex.run();
        Assert.assertArrayEquals(new Result[]{
            VoidResult.INSTANCE,
            new ValueResult(1),
            ExceptionResult.Companion.create(new NoSuchElementException()),
            ExceptionResult.Companion.create(new NoSuchElementException())
        }, ex.results);
    }
}