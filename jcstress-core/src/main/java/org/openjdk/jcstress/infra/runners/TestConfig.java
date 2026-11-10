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
package org.openjdk.jcstress.infra.runners;

import org.openjdk.jcstress.Verbosity;
import org.openjdk.jcstress.infra.processors.JCStressTestProcessor;
import org.openjdk.jcstress.os.SchedulingClass;
import org.openjdk.jcstress.Options;
import org.openjdk.jcstress.infra.TestInfo;
import org.openjdk.jcstress.os.CPUMap;
import org.openjdk.jcstress.vm.CompileMode;
import org.openjdk.jcstress.vm.VMSupport;

import java.io.PrintWriter;
import java.io.Serializable;
import java.util.List;

public class TestConfig implements Serializable {
    public final SpinLoopStyle spinLoopStyle;
    public final int threads;
    public final String name;
    public final String binaryName;
    public final String generatedRunnerName;
    public final List<String> jvmArgs;
    public final int forkId;
    public final int maxFootprintMB;
    public final List<String> actorNames;
    public final int compileMode;
    public final SchedulingClass shClass;
    public final int strideSize;
    public int strideCount;
    public CPUMap cpuMap;

    public void setCPUMap(CPUMap cpuMap) {
        this.cpuMap = cpuMap;
    }

    public TestConfig(Options opts, TestInfo info, int forkId, List<String> jvmArgs, int compileMode, SchedulingClass scl) {
        this.forkId = forkId;
        this.jvmArgs = jvmArgs;
        strideSize = opts.getStrideSize();
        strideCount = opts.getStrideCount();
        spinLoopStyle = opts.getSpinStyle();
        maxFootprintMB = opts.getMaxFootprintMb();
        threads = info.threads();
        name = info.name();
        binaryName = info.binaryName();
        generatedRunnerName = info.generatedRunner();
        actorNames = info.actorNames();
        this.compileMode = compileMode;
        shClass = scl;
    }

    public int getCompileMode() {
        return compileMode;
    }

    public SchedulingClass getSchedulingClass() {
        return shClass;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TestConfig that = (TestConfig) o;

        if (!name.equals(that.name)) return false;
        if (spinLoopStyle != that.spinLoopStyle) return false;
        if (strideSize != that.strideSize) return false;
        if (strideCount != that.strideCount) return false;
        if (threads != that.threads) return false;
        if (compileMode != that.compileMode) return false;
        if (!jvmArgs.equals(that.jvmArgs)) return false;
        if (!shClass.equals(that.shClass)) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    public void generateDirectives(PrintWriter pw, Verbosity verbosity) {
        pw.println("[");

        // The worker threads:
        // Avoid any inlining for worker threads: either wait for task loop
        // compilation and pick up from there, or avoid inlining the actor method
        // that might be interpreted.
        pw.println("  {");
        pw.println("    match: \"" + VoidThread.class.getName() + "::*\",");
        pw.println("    inline: \"-*::*\",");
        pw.println("  },");
        pw.println("  {");
        pw.println("    match: \"" + LongThread.class.getName() + "::*\",");
        pw.println("    inline: \"-*::*\",");
        pw.println("  },");
        pw.println("  {");
        pw.println("    match: \"" + CounterThread.class.getName() + "::*\",");
        pw.println("    inline: \"-*::*\",");
        pw.println("  },");

        // The task loop:
        pw.println("  {");
        pw.println("    match: [");
        pw.println("      \"*::" + JCStressTestProcessor.ITERATION_LOOP_PREFIX + "*\",");
        pw.println("      \"*::" + JCStressTestProcessor.SANITY_CHECK_PREFIX + "*\",");
        pw.println("    ],");

        // Avoid inlining the run loop, it should be compiled as separate hot code
        pw.println("    inline: \"-*::" + JCStressTestProcessor.STRIDE_LOOP_PREFIX + "*\",");
        pw.println("    inline: \"-*::" + JCStressTestProcessor.CHECK_LOOP_PREFIX + "*\",");

        // Force inline the auxiliary methods and classes in the run loop
        pw.println("    inline: \"+*::" + JCStressTestProcessor.CONSUME_PREFIX + "*\",");
        pw.println("    inline: \"+" + WorkerSync.class.getName() + "::*\",");
        pw.println("    inline: \"+java.util.concurrent.atomic.*::*\",");

        // Omit inlining of non-essential methods
        pw.println("    inline: \"-*::" + JCStressTestProcessor.CONSUME_NI_PREFIX + "*\",");

        // The test is running in resource-constrained JVM. Block the task loop execution until
        // compiled code is available. This would allow compilers to work in relative peace.
        pw.println("    BackgroundCompilation: false,");

        pw.println("  },");

        // Force inline everything from WorkerSync. WorkerSync does not use anything
        // too deeply, so inlining everything is fine.
        pw.println("  {");
        pw.println("    match: \"" + WorkerSync.class.getName() + "::*" + "\",");
        pw.println("    inline: \"+*::*\",");

        // The test is running in resource-constrained JVM. Block the WorkerSync execution until
        // compiled code is available. This would allow compilers to work in relative peace.
        pw.println("    BackgroundCompilation: false,");

        pw.println("  },");

        // The run loops:
        for (int a = 0; a < threads; a++) {
            String an = actorNames.get(a);

            pw.println("  {");
            pw.println("    match: [");
            pw.println("      \"*::" + JCStressTestProcessor.STRIDE_LOOP_PREFIX + an + "\",");
            pw.println("      \"*::" + JCStressTestProcessor.CHECK_LOOP_PREFIX + an + "\",");
            pw.println("    ],");
            pw.println("    inline: \"+*::" + JCStressTestProcessor.CONSUME_PREFIX + "*\",");
            pw.println("    inline: \"-*::" + JCStressTestProcessor.CONSUME_NI_PREFIX + "*\",");

            // Force inline of actor methods if run in compiled mode: this would inherit
            // compiler for them. Forbid inlining of actor methods in interpreted mode:
            // this would make sure that while actor methods are running in interpreter,
            // the run loop still runs in compiled mode, running faster. The call to interpreted
            // method would happen anyway, even though through c2i transition.
            if (CompileMode.isInt(compileMode, a)) {
                pw.println("    inline: \"-" + binaryName + "::" + an + "\",");
            } else {
                pw.println("    inline: \"+" + binaryName + "::" + an + "\",");
            }

            // Run loop should be compiled with C2? Forbid C1 compilation then.
            if (CompileMode.isC2(compileMode, a)) {
                pw.println("    c1: {");
                pw.println("      Exclude: true,");
                pw.println("    },");
            }

            // Run loop should be compiled with C1? Forbid C2 compilation then.
            if (CompileMode.isC1(compileMode, a)) {
                pw.println("    c2: {");
                pw.println("      Exclude: true,");
                pw.println("    },");
            }

            if (VMSupport.printAssemblyAvailable() && verbosity.printAssembly() && !CompileMode.isInt(compileMode, a)) {
                pw.println("    PrintAssembly: true,");
            }

            // The test is running in resource-constrained JVM. Block the run loop execution until
            // compiled code is available. This would allow compilers to work in relative peace.
            pw.println("    BackgroundCompilation: false,");

            pw.println("  },");
        }

        for (int a = 0; a < threads; a++) {
            String an = actorNames.get(a);

            pw.println("  {");
            pw.println("    match: \"" + binaryName + "::" + an + "\",");

            // Make sure actor is compiled with the target mode. Note that normally
            // we would wait for run loop to inline the actor, but we don't want
            // the actor thread to escape the compilation mode before that happens.
            // In interpreted mode, the inlining would not happen, so we definitely
            // need to forbid the compilation here.
            if (CompileMode.isInt(compileMode, a)) {
                // Should be interpreter? Forbid compilation completely.
                pw.println("    c1: {");
                pw.println("      Exclude: true,");
                pw.println("    },");
                pw.println("    c2: {");
                pw.println("      Exclude: true,");
                pw.println("    },");
            } else if (CompileMode.isC2(compileMode, a)) {
                // Should be compiled with C2? Forbid C1 compilation then.
                pw.println("    c1: {");
                pw.println("      Exclude: true,");
                pw.println("    },");
            } else if (CompileMode.isC1(compileMode, a)) {
                // Should be compiled with C1? Forbid C2 compilation then.
                pw.println("    c2: {");
                pw.println("      Exclude: true,");
                pw.println("    },");
            }
            pw.println("  },");
        }
        pw.println("]");
        pw.flush();
    }


    public String toDetailedTest() {
        //binaryName have correct $ instead of . in name; omitted
        //generatedRunnerName name with suffix (usually _Test_jcstress) omitted
        //super.toString() as TestConfig@hash - omitted
        StringBuilder verboseOutput = new StringBuilder(name);
        verboseOutput.append(" {")
                .append(actorNames)
                .append(", spinLoopStyle: ").append(spinLoopStyle)
                .append(", threads: ").append(threads)
                .append(", forkId: ").append(forkId)
                .append(", maxFootprintMB: ").append(maxFootprintMB)
                .append(", compileMode: ").append(compileMode)
                .append(", shClass: ").append(shClass)
                .append(", strideSize: ").append(strideSize)
                .append(", strideCount: ").append(strideCount)
                .append(", cpuMap: ").append(cpuMap)
                .append(", ").append(jvmArgs)
                .append("}");
        return verboseOutput.toString();
    }
}
