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

import org.openjdk.jcstress.infra.TestInfo;
import org.openjdk.jcstress.infra.collectors.*;
import org.openjdk.jcstress.infra.grading.ConsoleReportPrinter;
import org.openjdk.jcstress.infra.grading.ExceptionReportPrinter;
import org.openjdk.jcstress.infra.grading.TextReportPrinter;
import org.openjdk.jcstress.infra.grading.HTMLReportPrinter;
import org.openjdk.jcstress.infra.runners.TestConfig;
import org.openjdk.jcstress.infra.runners.TestList;
import org.openjdk.jcstress.os.*;
import org.openjdk.jcstress.os.topology.Topology;
import org.openjdk.jcstress.vm.CompileMode;
import org.openjdk.jcstress.vm.VMSupport;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * JCStress main entry point.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class JCStress {
    final PrintStream out;
    final Options opts;

    public JCStress(Options opts) {
        this.opts = opts;
        this.out = System.out;
    }

    public void run() throws Exception {
        ConfigsWithScheduler config = getConfigs();
        if (config == null) {
            return;
        }

        TimeBudget timeBudget = new TimeBudget(config.configs.size(), opts.timeBudget());
        timeBudget.printOn(out);

        ConsoleReportPrinter printer = new ConsoleReportPrinter(opts, new PrintWriter(out, true), config.configs.size(), timeBudget);
        DiskWriteCollector diskCollector = new DiskWriteCollector(opts.getResultFile());
        TestResultCollector mux = MuxCollector.of(printer, diskCollector);
        SerializedBufferCollector sink = new SerializedBufferCollector(mux);

        TestExecutor executor = new TestExecutor(opts.verbosity(), sink, config.scheduler, timeBudget);
        printer.setExecutor(executor);

        executor.runAll(config.configs);

        sink.close();
        diskCollector.close();

        printer.printFinishLine();

        out.println();
        out.println();

        parseResults();
    }

    private ConfigsWithScheduler getConfigs() {
        VMSupport.initFlags(opts);

        OSSupport.init();

        VMSupport.detectAvailableVMConfigs(opts.isSplitCompilation(), opts.getJvmArgs(), opts.getJvmArgsPrepend());
        if (VMSupport.getAvailableVMConfigs().isEmpty()) {
            out.println("FATAL: No JVM configurations to run with.");
            return null;
        }

        SortedSet<String> tests = getTests();

        Topology topology = Topology.get();
        out.println("Detecting CPU topology and computing scheduling classes:");
        topology.printStatus(out);
        out.println();

        out.println("  Scheduling classes for matching tests:");
        Scheduler scheduler = new Scheduler(topology, opts.getCPUCount());
        Map<Integer, List<SchedulingClass>> classes = computeSchedulingClasses(tests, scheduler);

        List<TestConfig> configs = prepareRunProgram(classes, tests);

        opts.printSettingsOn(out);

        if (configs.isEmpty()) {
            out.println("FATAL: No matching tests.");
            return null;
        }

        return new ConfigsWithScheduler(scheduler, configs);
    }

    private static class ConfigsWithScheduler {
        public final Scheduler scheduler;
        public final List<TestConfig> configs;

        public ConfigsWithScheduler(Scheduler scheduler, List<TestConfig> configs) {
            this.scheduler = scheduler;
            this.configs = configs;
        }
    }

    private Map<Integer, List<SchedulingClass>> computeSchedulingClasses(SortedSet<String> tests, Scheduler scheduler) {
        Map<Integer, List<SchedulingClass>> classes = new HashMap<>();
        SortedSet<Integer> actorCounts = computeActorCounts(tests);
        for (int a : actorCounts) {
            classes.put(a, scheduler.scheduleClasses(a, opts.getCPUCount(), opts.affinityMode()));
        }

        for (int a : actorCounts) {
            out.println("    " + a + " actors:");
            List<SchedulingClass> scls = classes.get(a);
            if (scls.isEmpty()) {
                out.println("      No scheduling is possible, these tests would not run.");
            } else {
                for (SchedulingClass scl : scls) {
                    out.println("      " + scl.toString());
                }
            }
        }
        out.println();
        return classes;
    }

    public void parseResults() throws Exception {
        InProcessCollector collector = new InProcessCollector();
        DiskReadCollector drc = new DiskReadCollector(opts.getResultFile(), collector);
        drc.dump();
        drc.close();

        new TextReportPrinter(opts, collector).work();
        new HTMLReportPrinter(opts, collector, out).work();
        new ExceptionReportPrinter(collector).work();
    }

    private SortedSet<Integer> computeActorCounts(Set<String> tests) {
        SortedSet<Integer> counts = new TreeSet<>();
        for (String test : tests) {
            TestInfo info = TestList.getInfo(test);
            counts.add(info.threads());
        }
        return counts;
    }

    private List<TestConfig> prepareRunProgram(Map<Integer, List<SchedulingClass>> scheduleClasses, Set<String> tests) {
        List<TestConfig> configs = new ArrayList<>();
        for (VMSupport.Config config : VMSupport.getAvailableVMConfigs()) {
            for (String test : tests) {
                TestInfo info = TestList.getInfo(test);
                for (SchedulingClass scl : scheduleClasses.get(info.threads())) {
                    if (opts.isSplitCompilation() && VMSupport.compilerDirectivesAvailable()) {
                        forkedSplit(configs, config, info, scl);
                    } else {
                        forkedUnified(configs, config, info, scl);
                    }
                }
            }
        }

        // Randomize the testing order
        Collections.shuffle(configs);

        return configs;
    }

    private boolean skipMode(int cm, VMSupport.Config config, int threads) {
        if (CompileMode.isUnified(cm)) {
            // Do not skip unified modes.
            return false;
        }
        // No C1/C2 runtime is available? Skip split compilation tests with C1/C2.
        if (!config.availableRuntimes().hasC2() && CompileMode.hasC2(cm, threads)) {
            return true;
        }
        if (!config.availableRuntimes().hasC1() && CompileMode.hasC1(cm, threads)) {
            return true;
        }
        // Config should be executed only when C1/C2 is available? Skip split compilation tests without them.
        if (config.requiredRuntimes().hasC2() && !CompileMode.hasC2(cm, threads)) {
            return true;
        }
        if (config.requiredRuntimes().hasC1() && !CompileMode.hasC1(cm, threads)) {
            return true;
        }
        // Do not skip by default.
        return false;
    }

    private void forkedSplit(List<TestConfig> testConfigs, VMSupport.Config config, TestInfo info, SchedulingClass scl) {
        for (int cm : CompileMode.casesFor(info.threads(), VMSupport.c1Available(), VMSupport.c2Available())) {
            if (skipMode(cm, config, info.threads())) {
                continue;
            }
            int forks = opts.getForks() * (config.stress() ? opts.getForksStressMultiplier() : 1);
            for (int f = 0; f < forks; f++) {
                testConfigs.add(new TestConfig(opts, info, f, config.args(), cm, scl));
            }
        }
    }

    private void forkedUnified(List<TestConfig> testConfigs, VMSupport.Config config, TestInfo info, SchedulingClass scl) {
        int forks = opts.getForks() * (config.stress() ? opts.getForksStressMultiplier() : 1);
        for (int f = 0; f < forks; f++) {
            testConfigs.add(new TestConfig(opts, info, f, config.args(), CompileMode.UNIFIED, scl));
        }
    }

    public SortedSet<String> getTests() {
        String filter = opts.getTestFilter();
        SortedSet<String> s = new TreeSet<>();

        Pattern pattern = Pattern.compile(filter);
        for (String testName : TestList.tests()) {
            if (pattern.matcher(testName).find()) {
                s.add(testName);
            }
        }
        return s;
   }

    public int listTests(Options opts) {
        JCStress.ConfigsWithScheduler configsWithScheduler = getConfigs();
        Set<String> testsToPrint = new TreeSet<>();
        for (TestConfig test : configsWithScheduler.configs) {
            if (opts.verbosity().printAllTests()) {
                testsToPrint.add(test.toDetailedTest());
            } else {
                testsToPrint.add(test.name);
            }
        }
        if (opts.verbosity().printAllTests()) {
            out.println("All matching tests combinations - " + testsToPrint.size());
        } else {
            out.println("All matching tests - " + testsToPrint.size());
        }
        for (String test : testsToPrint) {
            out.println(test);
        }
        return testsToPrint.size();
    }

}
