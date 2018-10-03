package com.devexperts.dxlab.lincheck.runner;

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

import com.devexperts.dxlab.lincheck.Actor;
import com.devexperts.dxlab.lincheck.Result;
import com.devexperts.dxlab.lincheck.Utils;
import com.devexperts.dxlab.lincheck.execution.ExecutionResult;
import com.devexperts.dxlab.lincheck.execution.ExecutionScenario;
import com.devexperts.dxlab.lincheck.strategy.Strategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * This runner executes parallel scenario' part in different threads.
 * It is pretty useful for stress testing or if you do not care about context switches.
 */
public class ParallelThreadsRunner extends Runner {
    private final List<TestThreadExecution> testThreadExecutions;
    private final ExecutorService executor;

    public ParallelThreadsRunner(ExecutionScenario scenario, Strategy strategy, Class<?> testClass, List<int[]> waits)
    {
        super(scenario, strategy, testClass);
        int nThreads = scenario.parallelExecution.size();
        // Create TestThreadExecution's
        boolean waitsEnabled = waits != null;
        testThreadExecutions = new ArrayList<>(nThreads);
        for (int t = 0; t < nThreads; t++) {
            List<Actor> actors = scenario.parallelExecution.get(t);
            testThreadExecutions.add(TestThreadExecutionGenerator.create(this, t, actors, waitsEnabled));
        }
        // Set waits if needed
        if (waitsEnabled) {
            for (int t = 0; t < nThreads; t++) {
                testThreadExecutions.get(t).waits = waits.get(t);
            }
        }
        // Fixed thread pool executor to run TestThreadExecution
        executor = Executors.newFixedThreadPool(nThreads, TestThread::new);
    }

    @Override
    public ExecutionResult run() throws InterruptedException {
        Object testInstance = Utils.createTestInstance(testClass);
        testThreadExecutions.forEach(ex -> ex.testInstance = testInstance);
        // Run init part
        List<Result> initResults = Utils.executeActors(testInstance, scenario.initExecution);
        // Run parallel part
        List<List<Result>> parallelResults = executor.invokeAll(testThreadExecutions).stream() // get futures
            .map(f -> {
                try {
                    return Arrays.asList(f.get()); // wait and get results
                } catch (InterruptedException | ExecutionException e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.toList());
        // Run post part
        List<Result> postResults = Utils.executeActors(testInstance, scenario.postExecution);
        // Return the execution result
        return new ExecutionResult(initResults, parallelResults, postResults);
    }

    @Override
    public void onStart(int iThread) {
        super.onStart(iThread);
        ((TestThread) Thread.currentThread()).iThread = iThread;
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    /**
     * All {@link TestThreadExecution}s are executing in this threads.
     */
    public class TestThread extends Thread {
        public int iThread;

        private TestThread(Runnable r) {
            super(r);
        }
    }
}
