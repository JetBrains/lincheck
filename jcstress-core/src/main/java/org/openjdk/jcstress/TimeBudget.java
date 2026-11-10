/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jcstress;

import org.openjdk.jcstress.infra.grading.ReportUtils;
import org.openjdk.jcstress.util.TimeValue;
import org.openjdk.jcstress.vm.VMSupport;

import java.io.PrintStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TimeBudget {

    static final int DEFAULT_PER_TEST_MS = Integer.getInteger("jcstress.timeBudget.defaultPerTestMs", 3000);
    static final int MIN_TIME_MS = Integer.getInteger("jcstress.timeBudget.minTimeMs", 30);
    static final int MAX_TIME_MS = Integer.getInteger("jcstress.timeBudget.maxTimeMs", 60_000);

    final long endTime;
    final int expectedTests;

    final AtomicInteger inflightTests;
    final AtomicInteger maxInflightTests;
    final AtomicInteger leftoverTests;
    final TimeValue budget;

    public TimeBudget(int expectedTests, TimeValue timeBudget) {
        this.budget = estimateDefault(expectedTests, timeBudget);
        this.expectedTests = expectedTests;
        this.endTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + budget.milliseconds();
        this.inflightTests = new AtomicInteger();
        this.maxInflightTests = new AtomicInteger();
        this.leftoverTests = new AtomicInteger(expectedTests);
    }

    public static TimeValue estimateDefault(int expectedTests, TimeValue timeBudget) {
        if (timeBudget != null) {
            return timeBudget;
        }

        // Assume the nearly worst case, all 4-actor tests taking the cores exclusively.
        long expectedTotalTime = (long) expectedTests * DEFAULT_PER_TEST_MS;
        long expectedPerTest = expectedTotalTime / Math.max(1, VMSupport.figureOutHotCPUs() / 8);
        return new TimeValue(expectedPerTest, TimeUnit.MILLISECONDS);
    }

    public void finishTest() {
        inflightTests.decrementAndGet();
        leftoverTests.decrementAndGet();
    }

    public void startTest() {
        int inflight = inflightTests.incrementAndGet();
        maxInflightTests.updateAndGet(x -> Math.max(x, inflight));
    }

    public int targetTestTimeMs() {
        if (isZero()) {
            return 0;
        }

        int parMult;

        int testsLeft = leftoverTests.get();
        int maxInflight = maxInflightTests.get();

        if (testsLeft >= maxInflight) {
            // Parallel multiplier is at least the number of currently
            // running parallel tests.
            parMult = inflightTests();
            if (parMult <= 0) {
                parMult = 1;
            }
        } else {
            // If the last tests are running, we should not cut down
            // the test time unnecessarily.
            parMult = maxInflight;
        }

        long timeLeft = timeLeftMs();

        long msPerTest = (timeLeft > 0 && testsLeft > 0) ?
                timeLeft / testsLeft * parMult :
                0;

        // Enforce reasonable target brackets and leave some time
        // for test infrastructure to run.
        if (msPerTest > MIN_TIME_MS * 2L) {
            msPerTest -= MIN_TIME_MS;
        }
        msPerTest = Math.max(MIN_TIME_MS, msPerTest);
        msPerTest = Math.min(MAX_TIME_MS, msPerTest);

        return (int)msPerTest;
    }

    public long timeLeftMs() {
        return endTime - TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    public int inflightTests() {
        return inflightTests.get();
    }

    public void printOn(PrintStream out) {
        out.println("  Time budget:");
        if (isZero()) {
            out.println("    Zero budget, sanity test mode");
        } else {
            out.println("    Initial completion estimate: " + ReportUtils.msToDate(timeLeftMs(), true));
            out.println("    Initial test time: " + targetTestTimeMs() + " ms");
        }
        out.println();
    }

    public boolean isZero() {
        return budget.isZero();
    }
}
