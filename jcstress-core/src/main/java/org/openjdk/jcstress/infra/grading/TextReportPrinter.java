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
import org.openjdk.jcstress.Verbosity;
import org.openjdk.jcstress.infra.Status;
import org.openjdk.jcstress.infra.collectors.InProcessCollector;
import org.openjdk.jcstress.infra.collectors.TestResult;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.*;
import java.util.function.Predicate;

/**
 * Prints HTML reports.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class TextReportPrinter {

    private final InProcessCollector collector;
    private final Verbosity verbosity;
    private final PrintWriter pw;
    private final Set<TestResult> emittedTests;

    public TextReportPrinter(Options opts, InProcessCollector collector) {
        this.collector = collector;
        this.pw = new PrintWriter(System.out, true);
        this.verbosity = opts.verbosity();
        this.emittedTests = new HashSet<>();
    }

    public void work() {
        emittedTests.clear();

        List<TestResult> byName = ReportUtils.mergedByName(collector.getTestResults());
        Collections.sort(byName, Comparator.comparing(TestResult::getName));

        pw.println("RUN RESULTS:");

        printXTests(byName,
                "Interesting tests",
                r -> r.status() == Status.NORMAL && r.grading().hasInteresting,
                true
        );

        printXTests(byName,
                "Failed tests",
                r -> r.status() == Status.NORMAL && !r.grading().isPassed,
                true
        );

        printXTests(byName,
                "Error tests",
                r -> r.status() != Status.NORMAL && r.status() != Status.API_MISMATCH,
                true
        );

        printXTests(byName,
                "All remaining tests",
                r -> !emittedTests.contains(r),
                verbosity.printAllTests());

        pw.println();
    }

    private void printXTests(List<TestResult> list,
                             String header,
                             Predicate<TestResult> predicate,
                             boolean emitDetails) {

        final long count = list.stream().filter(predicate).count();
        pw.println("  " + header + ": " + (count > 0 ? count + " matching test results." + (!emitDetails ? " Use -v to print them." : "") : "No matches."));

        if (emitDetails) {
            pw.println();
            boolean emitted = false;
            for (TestResult result : list) {
                if (predicate.test(result)) {
                    emitTest(result);
                    emitted = true;
                }
            }
            if (emitted) {
                pw.println();
            }
        }
    }

    public void emitTest(TestResult result) {
        emittedTests.add(result);
        ReportUtils.printResult(pw, result, true);
    }

}
