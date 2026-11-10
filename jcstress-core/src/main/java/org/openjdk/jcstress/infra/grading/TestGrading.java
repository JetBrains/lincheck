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

import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.infra.StateCase;
import org.openjdk.jcstress.infra.TestInfo;
import org.openjdk.jcstress.infra.collectors.TestResult;
import org.openjdk.jcstress.infra.runners.TestList;
import org.openjdk.jcstress.util.NonNullArrayList;

import java.util.*;

/**
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public class TestGrading {
    public boolean isPassed;
    public boolean hasInteresting;
    public final TreeMap<String, GradingResult> gradingResults;
    public final List<String> failureMessages;

    public static TestGrading grade(TestResult r) {
        return new TestGrading(r);
    }

    private TestGrading(TestResult r) {
        TestInfo test = TestList.getInfo(r.getName());
        gradingResults = new TreeMap<>();
        failureMessages = new NonNullArrayList<>();

        isPassed = true;
        hasInteresting = false;

        List<StateCase> unmatchedStates = new ArrayList<>();
        unmatchedStates.addAll(test.cases());

        for (String s : r.getStateKeys()) {

            // Figure out all matching cases, look for the exact match first:
            StateCase matched = null;
            for (StateCase c : test.cases()) {
                if (c.matchesExactly(s)) {
                    matched = c;
                    break;
                }
            }

            // Look for pattern match next:
            if (matched == null) {
                for (StateCase c : test.cases()) {
                    if (c.matches(s)) {
                        matched = c;
                        break;
                    }
                }
            }

            if (matched != null) {
                // Has the match:
                unmatchedStates.remove(matched);
            } else {
                // Otherwise, map to unmatched:
                matched = test.unmatched();
            }

            long count = r.getCount(s);
            Expect ex = matched.expect();
            isPassed &= passed(ex, count);
            hasInteresting |= hasInteresting(ex, count);
            failureMessages.add(failureMessage(s, ex, count, matched.description()));

            gradingResults.put(s,
                    new GradingResult(
                            s,
                            matched.expect(),
                            count,
                            matched.description()
                    ));
        }

        // Record unmatched cases from the test description itself
        for (StateCase c : unmatchedStates) {
            Expect ex = c.expect();
            isPassed &= passed(ex, 0);
            hasInteresting |= hasInteresting(ex, 0);
            failureMessages.add(failureMessage("N/A", ex, 0, c.description()));

            gradingResults.put(c.matchPattern(),
                    new GradingResult(
                            c.matchPattern(),
                            ex,
                            0,
                            c.description()
                    ));
        }
    }

    public static String failureMessage(String id, Expect expect, long count, String description) {
        if (passed(expect, count)) {
            return null;
        } else {
            switch (expect) {
                case ACCEPTABLE:
                case ACCEPTABLE_INTERESTING:
                    return null;
                case FORBIDDEN:
                    return "Observed forbidden state: " + id + (description == null ? "" : " (" + description + ")");
                case UNKNOWN:
                    return "Missing description";
                default:
                    return "Missing grading";
            }
        }
    }

    public static boolean passed(Expect expect, long count) {
        switch (expect) {
            case ACCEPTABLE:
            case ACCEPTABLE_INTERESTING:
                return true;
            case FORBIDDEN:
                return count == 0;
            case UNKNOWN:
                return false;
            default:
                throw new IllegalStateException("No grading for expect type = " + expect);
        }
    }

    private static boolean hasInteresting(Expect expect, long count) {
        switch (expect) {
            case ACCEPTABLE:
            case FORBIDDEN:
                return false;
            case ACCEPTABLE_INTERESTING:
                return count != 0;
            case UNKNOWN:
                return false;
            default:
                throw new IllegalStateException("No grading for expect type = " + expect);
        }
    }

}
