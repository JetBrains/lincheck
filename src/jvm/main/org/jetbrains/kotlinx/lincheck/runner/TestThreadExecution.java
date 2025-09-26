/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.runner;

import org.jetbrains.kotlinx.lincheck.Result;


/**
 * Instance of this class represents the test execution for ONE thread. Several instances should be run in parallel.
 * All implementations of this class should be generated via {@link TestThreadExecutionGenerator}.
 * The results and clocks are in the `results`  and `clocks` fields correspondingly.
 *
 * <p> This class should be public for having access from generated ones.
 */
@SuppressWarnings("unused")
public abstract class TestThreadExecution implements Runnable {
    // The following fields are assigned in TestThreadExecutionGenerator
    protected ExecutionScenarioRunner runner;
    public Object testInstance;
    protected Object[] objArgs;
    public TestThreadExecution[] allThreadExecutions;

    public Result[] results; // for ExecutionResult

    public int iThread; // thread ID of this execution
    public int[][] clocks; // for HBClock
    public volatile int curClock;
    public boolean useClocks;

    public TestThreadExecution() {}

    public TestThreadExecution(int iThread) {
        this.iThread = iThread;
    }

    public void readClocks(int currentActor) {
        for (int i = 0; i < allThreadExecutions.length; i++) {
            clocks[currentActor][i] = allThreadExecutions[i].curClock;
        }
    }

    // used in byte-code generation
    public void incClock() {
        curClock++;
    }

}