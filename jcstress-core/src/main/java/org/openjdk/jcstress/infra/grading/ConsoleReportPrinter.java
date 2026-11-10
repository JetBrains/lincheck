/*
 * Copyright (c) 2005, 2014, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.jcstress.infra.grading;

import org.openjdk.jcstress.Options;
import org.openjdk.jcstress.TestExecutor;
import org.openjdk.jcstress.TimeBudget;
import org.openjdk.jcstress.Verbosity;
import org.openjdk.jcstress.infra.collectors.TestResult;
import org.openjdk.jcstress.infra.collectors.TestResultCollector;
import org.openjdk.jcstress.vm.VMSupport;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Prints the test results to the console.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class ConsoleReportPrinter implements TestResultCollector {

    private static final Integer PRINT_INTERVAL_MS = Integer.getInteger("jcstress.console.printIntervalMs");

    private final Verbosity verbosity;
    private final PrintWriter output;

    private final long startTime;
    private final long expectedResults;

    private long sampleCount;

    private final long printIntervalMs;
    private long lastPrint;

    private static final int PROGRESS_COMPONENTS = 5;
    private final boolean progressInteractive;
    private final boolean progressAnsi;
    private boolean progressFirstLine;
    private final int[] progressLen;

    private long passed;
    private long failed;
    private long softErrors;
    private long hardErrors;
    private TestExecutor executor;
    private final int totalCpuCount;

    private final TimeBudget timeBudget;

    public ConsoleReportPrinter(Options opts, PrintWriter pw, long expectedForks, TimeBudget tb) {
        this.output = pw;
        this.expectedResults = expectedForks;
        totalCpuCount = opts.getCPUCount();
        verbosity = opts.verbosity();
        progressLen = new int[PROGRESS_COMPONENTS];
        Arrays.fill(progressLen, 1);

        progressFirstLine = true;
        progressInteractive = (System.console() != null);
        progressAnsi = VMSupport.isLinux();
        output.println("  Attached the " + (progressInteractive ? "interactive console" : "non-interactive output stream") + ".");

        printIntervalMs = (PRINT_INTERVAL_MS != null) ?
                PRINT_INTERVAL_MS :
                progressInteractive ? 1_000 : 15_000;

        output.println("  Printing the progress line at most every " + printIntervalMs + " milliseconds.");
        output.println();

        startTime = System.nanoTime();

        timeBudget = tb;
    }

    @Override
    public synchronized void add(TestResult r) {
        sampleCount += r.getTotalCount();
        printResult(r);
    }

    private void printResult(TestResult r) {
        TestGrading grading = r.grading();

        boolean inHardError = false;
        switch (r.status()) {
            case TIMEOUT_ERROR:
            case CHECK_TEST_ERROR:
            case TEST_ERROR:
            case VM_ERROR:
                hardErrors++;
                inHardError = true;
                break;
            case API_MISMATCH:
                softErrors++;
                break;
            case NORMAL:
                if (grading.isPassed) {
                    passed++;
                } else {
                    failed++;
                }
                break;
            default:
                throw new IllegalStateException("Illegal status: " + r.status());
        }

        boolean shouldPrintResults =
                inHardError ||
                !grading.isPassed ||
                grading.hasInteresting ||
                verbosity.printAllTests();

        boolean shouldPrintStatusLine =
                shouldPrintResults ||
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastPrint) >= printIntervalMs;

        if (shouldPrintStatusLine) {
            clearStatusLine();
        }

        if (shouldPrintResults) {
            if (!progressInteractive) {
                output.println();
            }
            ReportUtils.printResult(output, r, false);
        }

        if (shouldPrintStatusLine) {
            printStatusLine();
        }
    }

    private void printStatusLine() {
        long currentTime = System.nanoTime();
        final int cpus = executor.getCpus();

        String l0 = timeBudget.isZero() ? "(Sanity test mode)" :
                String.format("(Time: %s, %d tests in flight, %d ms per test)",
                        ReportUtils.msToDate(timeBudget.timeLeftMs(), false),
                        timeBudget.inflightTests() + 1,
                        timeBudget.targetTestTimeMs());
        String l1 = String.format("(Sampling Rate: %s)",
                computeSpeed());
        String l2 = String.format("(JVMs: %d starting, %d running, %d finishing)",
                executor.getJVMsStarting(), executor.getJVMsRunning(), executor.getJVMsFinishing());
        String l3 = String.format("(CPUs: %d configured, %d allocated)",
                totalCpuCount, cpus);
        String l4 = String.format("(Results: %d planned; %d passed, %d failed, %d soft errs, %d hard errs)",
                expectedResults, passed, failed, softErrors, hardErrors);

        if (!progressInteractive || progressAnsi) {
            progressLen[0] = l0.length();
            progressLen[1] = l1.length();
            progressLen[2] = l2.length();
            progressLen[3] = l3.length();
            progressLen[4] = l4.length();

            output.println(l0);
            output.println(l1);
            output.println(l2);
            output.println(l3);
            output.println(l4);
        } else {
            output.printf("%s %s %s %s %s", l0, l1, l2, l3, l4);
            progressLen[0] = l0.length() + l1.length() + l2.length() + l3.length() + l4.length() + 4;
        }

        if (!progressInteractive) {
            output.println();
        }
        output.flush();
        lastPrint = currentTime;

    }

    private void clearStatusLine() {
        if (progressFirstLine) {
            progressFirstLine = false;
            return;
        }
        if (progressInteractive) {
            if (progressAnsi) {
                for (int i = progressLen.length - 1; i >= 0; i--) {
                    output.printf("\033[F%" + progressLen[i] + "s\r", "");
                }
            } else {
                output.printf("\r%" + progressLen[0] + "s\r", "");
            }
        }
    }

    public void printFinishLine() {
        clearStatusLine();
        printStatusLine();
    }

    private String computeSpeed() {
        if (sampleCount == 0) {
            return "N/A";
        }

        long timeSpent = System.nanoTime() - startTime;
        double v = 1.0 * TimeUnit.SECONDS.toNanos(1) * sampleCount / timeSpent;

        final long K = 1000;
        final long M = 1000*K;
        final long G = 1000*M;
        final long T = 1000*G;

        if (v > T) {
            return String.format("%3.2f T/sec", v / T);
        }

        if (v > G) {
            return String.format("%3.2f G/sec", v / G);
        }

        if (v > M) {
            return String.format("%3.2f M/sec", v / M);
        }

        if (v > K) {
            return String.format("%3.2f K/sec", v / K);
        }

        return String.format("%3.2f #/sec", v);
    }

    public void setExecutor(TestExecutor executor) {
        this.executor = executor;
    }
}
