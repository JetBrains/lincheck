/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.infra.StateCase;
import org.openjdk.jcstress.infra.Status;
import org.openjdk.jcstress.infra.TestInfo;
import org.openjdk.jcstress.infra.collectors.TestResult;
import org.openjdk.jcstress.infra.runners.TestConfig;
import org.openjdk.jcstress.infra.runners.TestList;
import org.openjdk.jcstress.os.SchedulingClass;
import org.openjdk.jcstress.os.CPUMap;
import org.openjdk.jcstress.util.*;
import org.openjdk.jcstress.vm.CompileMode;

import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ReportUtils {

    public static List<TestResult> mergedByConfig(Collection<TestResult> src) {
        Multimap<TestConfig, TestResult> multiResults = new HashMultimap<>();
        for (TestResult r : src) {
            multiResults.put(r.getConfig(), r);
        }

        List<TestResult> results = new ArrayList<>();
        for (TestConfig config : multiResults.keys()) {
            Collection<TestResult> mergeable = multiResults.get(config);
            TestResult root = merged(config, mergeable);
            results.add(root);
        }

        return results;
    }

    public static List<TestResult> mergedByName(Collection<TestResult> src) {
        Multimap<String, TestResult> multiResults = new HashMultimap<>();
        for (TestResult r : src) {
            multiResults.put(r.getConfig().name, r);
        }

        List<TestResult> results = new ArrayList<>();
        for (String name : multiResults.keys()) {
            Collection<TestResult> mergeable = multiResults.get(name);
            TestResult root = merged(mergeable.iterator().next().getConfig(), mergeable);
            results.add(root);
        }

        return results;
    }

    public static Multimap<String, TestResult> byName(Collection<TestResult> src) {
        Multimap<String, TestResult> result = new HashMultimap<>();
        for (TestResult r : mergedByConfig(src)) {
            result.put(r.getName(), r);
        }
        return result;
    }

    private static TestResult merged(TestConfig config, Collection<TestResult> mergeable) {
        Counter<String> counter = new Counter<>();

        List<String> messages = new ArrayList<>();
        List<String> vmOuts = new ArrayList<>();
        List<String> vmErrs = new ArrayList<>();

        Status status = Status.NORMAL;
        Environment env = null;
        for (TestResult r : mergeable) {
            env = r.getEnv();
            status = status.combine(r.status());
            counter.merge(r.getCounter());
            messages.addAll(r.getMessages());
            vmOuts.addAll(r.getVmOut());
            vmErrs.addAll(r.getVmErr());
        }

        TestResult root = new TestResult(status);
        root.setConfig(config);
        root.setEnv(env);
        root.addState(counter);
        root.addMessages(messages);
        root.addVMOuts(vmOuts);
        root.addVMErrs(vmErrs);

        return root;
    }

    public static void printResult(PrintWriter pw, TestResult r, boolean finalResults) {
        TestConfig config = r.getConfig();

        String label = StringUtils.leftPadDash("[" + ReportUtils.statusToLabel(r) + "]", 15);
        String testName = StringUtils.chunkName(r.getName());
        pw.printf("%15s %s%n", label, testName);
        pw.println();
        if (finalResults) {
            pw.println("  Results across all configurations:");
        } else {
            pw.format("  Scheduling class:%n");
            pw.println(SchedulingClass.description(config.getSchedulingClass(), config.actorNames));
            pw.format("  CPU allocation: %s%n", CPUMap.description(config.cpuMap, config.actorNames));
            pw.format("  Compilation: %s%n", CompileMode.description(config.getCompileMode(), config.actorNames));
            pw.format("  JVM args: %s%n", config.jvmArgs);
            pw.format("  Fork: #%d%n", config.forkId + 1);
        }
        pw.println();

        if (!r.isEmpty()) {
            final String headResult = "RESULT";
            final String headSamples = "SAMPLES";
            final String headFreq = "FREQ";
            final String headExpect = "EXPECT";
            final String headDesc = "DESCRIPTION";

            int idLen = headResult.length();
            int samplesLen = headSamples.length();
            int freqLen = Math.max(7, headFreq.length());
            int expectLen = headExpect.length();
            int descLen = 60;

            for (String s : r.getStateKeys()) {
                idLen = Math.max(idLen, s.length());
                samplesLen = Math.max(samplesLen, String.format("%,d", r.getCount(s)).length());
                expectLen = Math.max(expectLen, Expect.UNKNOWN.toString().length());
            }

            TestInfo test = TestList.getInfo(r.getName());
            for (StateCase c : test.cases()) {
                idLen = Math.max(idLen, c.matchPattern().length());
                expectLen = Math.max(expectLen, c.expect().toString().length());
            }
            expectLen = Math.max(expectLen, test.unmatched().expect().toString().length());

            idLen += 2;
            samplesLen += 2;
            freqLen += 2;
            expectLen += 2;

            pw.printf("%" + idLen + "s%" + samplesLen + "s%" + freqLen + "s%" + expectLen + "s  %s%n",
                    headResult, headSamples, headFreq, headExpect, headDesc);

            TestGrading grade = r.grading();
            long totalSamples = 0;
            for (GradingResult gradeRes : grade.gradingResults.values()) {
                totalSamples += gradeRes.count;
            }

            if (totalSamples == 0) {
                totalSamples = 1;
            }

            for (GradingResult gradeRes : grade.gradingResults.values()) {
                pw.printf("%" + idLen + "s%," + samplesLen + "d%" + freqLen + "s%" + expectLen + "s  %s%n",
                        StringUtils.cutoff(gradeRes.id, idLen),
                        gradeRes.count,
                        StringUtils.percent(gradeRes.count, totalSamples, 2),
                        gradeRes.expect,
                        StringUtils.cutoff(gradeRes.description, descLen));
            }

            pw.println();
        }

        boolean errMsgsPrinted = false;
        for (String data : r.getMessages()) {
            if (skipMessage(data)) continue;
            if (!errMsgsPrinted) {
                pw.println("  Messages:");
                errMsgsPrinted = true;
            }
            pw.println("    " + data);
        }
        if (errMsgsPrinted) {
            pw.println();
        }

        boolean vmOutPrinted = false;
        for (String data : r.getVmOut()) {
            if (skipMessage(data)) continue;
            if (!vmOutPrinted) {
                pw.println("  VM output stream:");
                vmOutPrinted = true;
            }
            pw.println("    " + data);
        }
        if (vmOutPrinted) {
            pw.println();
        }

        boolean vmErrPrinted = false;
        for (String data : r.getVmErr()) {
            if (skipMessage(data)) continue;
            if (!vmErrPrinted) {
                pw.println("  VM error stream:");
                vmErrPrinted = true;
            }
            pw.println("    " + data);
        }
        if (vmErrPrinted) {
            pw.println();
        }
    }

    public static boolean skipMessage(String data) {
        if (data == null) {
            return true;
        }

        if (data.startsWith("Warning: 'NoSuchMethodError' on register of sun.hotspot.WhiteBox")) {
            return true;
        }

        if (data.contains("compiler directives added")) {
            return true;
        }

        if (data.contains("Option UseBiasedLocking was deprecated")) {
            return true;
        }

        return false;
    }

    public static String statusToLabel(TestResult result) {
        switch (result.status()) {
            case TIMEOUT_ERROR:
                return "TIMEOUT";
            case CHECK_TEST_ERROR:
            case TEST_ERROR:
                return "ERROR";
            case VM_ERROR:
                return "VM ERROR";
            case API_MISMATCH:
                return "SKIPPED";
            case NORMAL:
                if (result.grading().isPassed) {
                    return "OK";
                } else {
                    return "FAILED";
                }
            default:
                throw new IllegalStateException("Illegal status: " + result.status());
        }
    }

    public static boolean statusToPassed(TestResult result) {
        switch (result.status()) {
            case TIMEOUT_ERROR:
            case CHECK_TEST_ERROR:
            case TEST_ERROR:
            case VM_ERROR:
            case API_MISMATCH:
                return false;
            case NORMAL:
                return result.grading().isPassed;
            default:
                throw new IllegalStateException("Illegal status: " + result.status());
        }
    }

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("E, yyyy-MM-dd HH:mm:ss");

    public static String msToDate(long ms, boolean finalDate) {
        LocalDateTime ldt = LocalDateTime.now().plus(ms, ChronoUnit.MILLIS);

        String result = "";

        boolean overtime = ms < 0;
        if (overtime) {
            ms = -ms;
        }

        long days = TimeUnit.MILLISECONDS.toDays(ms);
        if (days > 0) {
            result += days + "d+";
            ms -= TimeUnit.DAYS.toMillis(days);
        }
        long hours = TimeUnit.MILLISECONDS.toHours(ms);
        ms -= TimeUnit.HOURS.toMillis(hours);

        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms);
        ms -= TimeUnit.MINUTES.toMillis(minutes);

        long seconds = TimeUnit.MILLISECONDS.toSeconds(ms);

        if (overtime) {
            result += "overtime ";
        }
        result += String.format("%02d:%02d:%02d", hours, minutes, seconds);
        if (!overtime) {
            result += " left";
        }
        if (finalDate) {
            result += "; at " + ldt.format(DATE_TIME_FORMATTER);
        }
        return result;
    }

}
