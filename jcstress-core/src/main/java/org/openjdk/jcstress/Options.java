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
package org.openjdk.jcstress;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.openjdk.jcstress.infra.runners.SpinLoopStyle;
import org.openjdk.jcstress.os.AffinityMode;
import org.openjdk.jcstress.util.OptionFormatter;
import org.openjdk.jcstress.util.StringUtils;
import org.openjdk.jcstress.util.TimeValue;
import org.openjdk.jcstress.vm.VMSupport;

import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Options.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class Options {
    private String resultDir;
    private String testFilter;
    private int strideSize;
    private int strideCount;
    private final String[] args;
    private boolean parse;
    private boolean list;
    private Verbosity verbosity;
    private int cpuCount;
    private int heapPerFork;
    private int forks;
    private int forksStressMultiplier;
    private SpinLoopStyle spinStyle;
    private String resultFile;
    private List<String> jvmArgs;
    private List<String> jvmArgsPrepend;
    private boolean splitCompilation;
    private AffinityMode affinityMode;
    private boolean pretouchHeap;
    private TimeValue timeBudget;

    public Options(String[] args) {
        this.args = args;
    }

    public boolean parse() throws IOException {
        OptionParser parser = new OptionParser();
        parser.formatHelpWith(new OptionFormatter());

        OptionSpec<String> result = parser.accepts("r", "Target destination to put the report into.")
                .withRequiredArg().ofType(String.class).describedAs("dir");

        OptionSpec<String> parse = parser.accepts("p", "Re-run parser on the result file. This will not run any tests.")
                .withRequiredArg().ofType(String.class).describedAs("result file");

        OptionSpec<Boolean> list = parser.accepts("l", "List the available tests matching the requested settings, " +
                        "after all filters (like CPU count) are applied. In verbose mode it prints all real combinations which will run.")
                .withOptionalArg().ofType(Boolean.class).describedAs("bool");

        OptionSpec<String> testFilter = parser.accepts("t", "Regexp selector for tests.")
                .withRequiredArg().ofType(String.class).describedAs("regexp");

        OptionSpec<Integer> strideSize = parser.accepts("strideSize", "Internal stride size. Larger value decreases " +
                "the synchronization overhead, but also reduces the number of collisions.")
                .withRequiredArg().ofType(Integer.class).describedAs("N");

        OptionSpec<Integer> strideCount = parser.accepts("strideCount", "Internal stride count per epoch. " +
                "Larger value increases cache footprint.")
                .withRequiredArg().ofType(Integer.class).describedAs("N");

        OptionSpec<Integer> optTime = parser.accepts("time", "(Deprecated, to be removed in next releases.)")
                .withRequiredArg().ofType(Integer.class).describedAs("ms");

        OptionSpec<Integer> optIters = parser.accepts("iters", "(Deprecated, to be removed in next releases.)")
                .withRequiredArg().ofType(Integer.class).describedAs("N");

        OptionSpec<Integer> cpus = parser.accepts("c", "Number of CPUs to use. Defaults to all CPUs in the system. " +
                "Reducing the number of CPUs limits the amount of resources (including memory) the run is using.")
                .withRequiredArg().ofType(Integer.class).describedAs("N");

        OptionSpec<Integer> heapPerFork = parser.accepts("hs", "Java heap size per fork, in megabytes. This " +
                "affects the stride size: maximum footprint will never be exceeded, regardless of min/max stride sizes.")
                .withRequiredArg().ofType(Integer.class).describedAs("MB");

        OptionSpec<SpinLoopStyle> spinStyle = parser.accepts("spinStyle", "Busy loop wait style. " +
                "HARD = hard busy loop; THREAD_YIELD = use Thread.yield(); THREAD_SPIN_WAIT = use Thread.onSpinWait(); LOCKSUPPORT_PARK_NANOS = use LockSupport.parkNanos().")
                .withRequiredArg().ofType(SpinLoopStyle.class).describedAs("style");

        OptionSpec<Integer> forks = parser.accepts("f", "Should fork each test N times. \"0\" to run in the embedded mode " +
                "with occasional forking.")
                .withOptionalArg().ofType(Integer.class).describedAs("count");

        OptionSpec<Integer> forksStressMultiplier = parser.accepts("fsm", "Fork multiplier for randomized/stress tests. " +
                "This allows more efficient randomized testing, as each fork would use a different seed.")
                .withOptionalArg().ofType(Integer.class).describedAs("multiplier");

        OptionSpec<String> optModeStr = parser.accepts("m", "(Deprecated, to be removed in next releases).")
                .withRequiredArg().ofType(String.class).describedAs("mode");

        OptionSpec<String> optJvmArgs = parser.accepts("jvmArgs", "Use given JVM arguments. This disables JVM flags auto-detection, " +
                "and runs only the single JVM mode. Either a single space-separated option line, or multiple options are accepted. " +
                "This option only affects forked runs.")
                .withRequiredArg().ofType(String.class).describedAs("string");

        OptionSpec<String> optJvmArgsPrepend = parser.accepts("jvmArgsPrepend", "Prepend given JVM arguments to auto-detected configurations. " +
                "Either a single space-separated option line, or multiple options are accepted. " +
                "This option only affects forked runs.")
                .withRequiredArg().ofType(String.class).describedAs("string");

        OptionSpec<Boolean> optSplitCompilation = parser.accepts("sc", "Use split per-actor compilation mode, if available.")
                .withOptionalArg().ofType(Boolean.class).describedAs("bool");

        OptionSpec<AffinityMode> optAffinityMode = parser.accepts("af", "Use the specific affinity mode, if available.")
                .withOptionalArg().ofType(AffinityMode.class).describedAs("mode");

        OptionSpec<Boolean> optPretouchHeap = parser.accepts("pth", "Pre-touch Java heap, if possible.")
                .withOptionalArg().ofType(Boolean.class).describedAs("bool");

        OptionSpec<TimeValue> optTimeBudget = parser.accepts("tb", "Time budget to run the tests. Harness code would try to fit the entire " +
                "run in the desired timeframe. This value is expected to be reasonable, as it is not guaranteed that tests would succeed " +
                "in arbitrarily low time budget. If not set, harness would try to decide a reasonable time, given the number of tests to run. " +
                "Common time suffixes (s/m/h/d) are accepted.")
                .withRequiredArg().ofType(TimeValue.class).describedAs("time");

        parser.accepts("v", "Be verbose.");
        parser.accepts("vv", "Be extra verbose.");
        parser.accepts("vvv", "Be extra extra verbose.");
        parser.accepts("h", "Print this help.");

        OptionSet set;
        try {
            set = parser.parse(args);
        } catch (OptionException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.err.println();
            parser.printHelpOn(System.err);
            return false;
        }

        if (set.has("h")) {
            parser.printHelpOn(System.out);
            return false;
        }

        this.resultDir = orDefault(set.valueOf(result), "results/");
        if (!resultDir.endsWith("/")) {
            resultDir += "/";
        }

        this.testFilter = orDefault(set.valueOf(testFilter), ".*");

        this.parse = orDefault(set.has(parse), false);
        if (this.parse) {
            this.resultFile = set.valueOf(parse);
        } else {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).format(new Date());
            this.resultFile = "jcstress-results-" + timestamp + ".bin.gz";
        }
        this.list = orDefault(set.has(list), false);
        if (set.has("vvv")) {
            this.verbosity = new Verbosity(3);
        } else if (set.has("vv")) {
            this.verbosity = new Verbosity(2);
        } else if (set.has("v")) {
            this.verbosity = new Verbosity(1);
        } else {
            this.verbosity = new Verbosity(0);
        }

        int totalCpuCount = VMSupport.figureOutHotCPUs();
        cpuCount = orDefault(set.valueOf(cpus), totalCpuCount);

        if (cpuCount > totalCpuCount) {
            System.err.println("Requested to use " + cpuCount + " CPUs, but system has only " + totalCpuCount + " CPUs.");
            System.err.println();
            parser.printHelpOn(System.err);
            return false;
        }

        this.spinStyle = orDefault(set.valueOf(spinStyle), SpinLoopStyle.THREAD_SPIN_WAIT);

        this.timeBudget = optTimeBudget.value(set);

        if (timeBudget != null && timeBudget.isZero()) {
            // Special, extra-fast mode, good only for sanity testing
            this.forks = 1;
            this.forksStressMultiplier = 1;
            this.strideSize = 1;
            this.strideCount = 1;
            this.pretouchHeap = false;
        } else {
            this.forks = 1;
            this.forksStressMultiplier = 1;
            this.strideSize = 256;
            this.strideCount = 40;
            this.pretouchHeap = true;
        }

        if (optModeStr.value(set) != null) {
            System.err.println("-m option is not supported anymore, please use -tb.");
            System.err.println();
            parser.printHelpOn(System.err);
            return false;
        }

        if (optTime.value(set) != null) {
            System.err.println("-time option is not supported anymore, please use -tb.");
            System.err.println();
            parser.printHelpOn(System.err);
            return false;
        }

        if (optIters.value(set) != null) {
            System.err.println("-iters option is not supported anymore, please use -tb.");
            System.err.println();
            parser.printHelpOn(System.err);
            return false;
        }

        // override these, if present
        this.forks = orDefault(set.valueOf(forks), this.forks);
        this.forksStressMultiplier = orDefault(set.valueOf(forksStressMultiplier), this.forksStressMultiplier);
        this.strideSize = orDefault(set.valueOf(strideSize), this.strideSize);
        this.strideCount = orDefault(set.valueOf(strideCount), this.strideCount);
        this.pretouchHeap = orDefault(set.valueOf(optPretouchHeap), this.pretouchHeap);

        this.heapPerFork = orDefault(set.valueOf(heapPerFork), 256);

        this.jvmArgs = processArgs(optJvmArgs, set);
        this.jvmArgsPrepend = processArgs(optJvmArgsPrepend, set);

        this.splitCompilation = orDefault(set.valueOf(optSplitCompilation), true);
        this.affinityMode = orDefault(set.valueOf(optAffinityMode), AffinityMode.LOCAL);

        return true;
    }

    private List<String> processArgs(OptionSpec<String> op, OptionSet set) {
        if (set.hasArgument(op)) {
            try {
                List<String> vals = op.values(set);
                if (vals.size() != 1) {
                    return vals;
                } else {
                    return StringUtils.splitQuotedEscape(op.value(set));
                }
            } catch (OptionException e) {
                return StringUtils.splitQuotedEscape(op.value(set));
            }
        } else {
            return Collections.emptyList();
        }
    }

    private <T> T orDefault(T t, T def) {
        return (t != null) ? t : def;
    }

    public int getForks() {
        return forks;
    }

    public int getForksStressMultiplier() {
        return forksStressMultiplier;
    }

    public void printSettingsOn(PrintStream out) {
        out.println("  Test configuration:");
        out.printf("    Hardware CPUs in use: %d%n", getCPUCount());
        out.printf("    Spinning style: %s%n", getSpinStyle());
        out.printf("    Test selection: \"%s\"%n", getTestFilter());
        out.printf("    Forks per test: %d normal, %d stress%n", getForks(), getForks()*getForksStressMultiplier());
        out.printf("    Test stride: %d strides x %d tests, but taking no more than %d Mb%n", getStrideCount(), getStrideSize(), getMaxFootprintMb());
        out.printf("    Test result blob: \"%s\"%n", resultFile);
        out.printf("    Test results: \"%s\"%n", resultDir);
        out.println();
    }

    public int getStrideSize() {
        return strideSize;
    }

    public int getStrideCount() {
        return strideCount;
    }

    public String getResultDest() {
        return resultDir;
    }

    public SpinLoopStyle getSpinStyle() {
        switch (spinStyle) {
            case HARD:
            case THREAD_YIELD:
            case LOCKSUPPORT_PARK_NANOS:
                return spinStyle;
            case THREAD_SPIN_WAIT:
                if (VMSupport.spinWaitHintAvailable()) {
                    return spinStyle;
                } else {
                    return SpinLoopStyle.HARD;
                }
            default:
                throw new IllegalStateException("Unhandled spin style: " + spinStyle);
        }
    }

    public boolean shouldParse() {
        return parse;
    }

    public boolean shouldList() {
        return list;
    }

    public String getTestFilter() {
        return testFilter;
    }

    public Verbosity verbosity() {
        return verbosity;
    }

    public int getCPUCount() {
        return cpuCount;
    }

    public String getResultFile() {
        return resultFile;
    }

    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    public List<String> getJvmArgsPrepend() {
        return jvmArgsPrepend;
    }

    public int getHeapPerForkMb() {
        return heapPerFork;
    }

    public int getMaxFootprintMb() {
        // The tests can copy the entirety of their state, which means
        // we need at least twice the space in heap. The copied state
        // may also fragment the heap, especially with potential humongous
        // allocations, which can also take "twice" the space in the heap.
        //
        // Adding some slack for testing infra itself, we better allocate
        // about a quarter of heap size.
        return getHeapPerForkMb() / 4;
    }

    public boolean isSplitCompilation() {
        return splitCompilation;
    }

    public AffinityMode affinityMode() {
        return affinityMode;
    }

    public boolean isPretouchHeap() {
        return pretouchHeap;
    }

    public TimeValue timeBudget() { return timeBudget; }

}
