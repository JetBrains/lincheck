package com.devexperts.dxlab.lincheck.stress;

/*
 * #%L
 * core
 * %%
 * Copyright (C) 2015 - 2017 Devexperts, LLC
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
import com.devexperts.dxlab.lincheck.Strategy;
import com.devexperts.dxlab.lincheck.Utils;
import com.devexperts.dxlab.lincheck.verifier.Verifier;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.stream.Collectors;

public class StressStrategy extends Strategy {
    private static final int MAX_WAIT = 1000;

    private final Random random = new Random(0);
    private final int invocations;
    private final int nThreads;

    public StressStrategy(Object testInstance, Method resetMethod, List<List<Actor>> actorsPerThread,
        Verifier verifier, StressCTestConfiguration testCfg)
    {
        super(testInstance, resetMethod, actorsPerThread, verifier);
        this.invocations = testCfg.invocationsPerIteration;
        this.nThreads = testCfg.getThreads();
    }

    @Override
    public void run() throws InterruptedException {
        // Reusable phaser
        final Phaser phaser = new Phaser(actorsPerThread.size());
        // Create TestThreadExecution's
        List<TestThreadExecution> testThreadExecutions = actorsPerThread.stream()
            .map(actors -> TestThreadExecutionGenerator.create(testInstance, phaser, actors, false))
            .collect(Collectors.toList());
        // Fixed thread pool executor to run TestThreadExecution
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        try {
            // Run invocations
            for (int invocation = 0; invocation < invocations; invocation++) {
                // Reset the state of test
                Utils.invokeReset(resetMethod, testInstance);
                // Specify waits
                int maxWait = (int) ((float) invocation * MAX_WAIT / invocations) + 1;
                for (int i = 0; i < testThreadExecutions.size(); i++) {
                    TestThreadExecution ex = testThreadExecutions.get(i);
                    ex.waits = random.ints(actorsPerThread.get(i).size(), 0, maxWait).toArray();
                }
                // Run multithreaded test and get operation results for each thread
                List<List<Result>> results = pool.invokeAll(testThreadExecutions).stream() // get futures
                    .map(f -> {
                        try {
                            return Arrays.asList(f.get()); // wait and get results
                        } catch (InterruptedException | ExecutionException e) {
                            throw new IllegalStateException(e);
                        }
                    }).collect(Collectors.toList()); // and store results as list
                // Check that results are correct
                verifier.verifyResults(results);
            }
        } finally {
            pool.shutdown();
        }
    }
}
