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
package org.openjdk.jcstress.infra.runners;

import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.infra.StateCase;
import org.openjdk.jcstress.infra.TestInfo;
import org.openjdk.jcstress.util.TestLineReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Pattern;

public class TestList {

    public static final String LIST = "/META-INF/TestList";

    private static volatile Map<String, TestInfo> tests;

    private static Map<String, TestInfo> getTests() {
        if (tests == null) {
            Expect[] expectValues = Expect.values();
            Map<String, TestInfo> m = new HashMap<>();

            try (InputStream stream = TestList.class.getResourceAsStream(LIST);
                 InputStreamReader isr = new InputStreamReader(stream);
                 BufferedReader reader = new BufferedReader(isr)) {

                String line;
                while ((line = reader.readLine()) != null) {
                    TestLineReader read = new TestLineReader(line);

                    if (read.isCorrect()) {
                        String name = read.nextString();
                        String binaryName = read.nextString();
                        String runner = read.nextString();
                        String description = read.nextString();

                        List<String> actorNames = new ArrayList<>();
                        int actorCount = read.nextInt();
                        for (int c = 0; c < actorCount; c++) {
                            actorNames.add(read.nextString());
                        }

                        boolean requiresFork = read.nextBoolean();
                        int caseCount = read.nextInt();

                        TestInfo testInfo = new TestInfo(name, binaryName, runner, description, actorCount, actorNames, requiresFork);
                        m.put(name, testInfo);

                        for (int c = 0; c < caseCount; c++) {
                            Expect expect = expectValues[read.nextInt()];
                            String desc = read.nextString();
                            int stateCount = read.nextInt();
                            for (int s = 0; s < stateCount; s++) {
                                String regex = read.nextString();
                                Pattern pattern = Pattern.compile(regex);
                                testInfo.addCase(new StateCase(pattern, expect, desc));
                            }
                        }

                        int refCount = read.nextInt();
                        for (int c = 0; c < refCount; c++) {
                            testInfo.addRef(read.nextString());
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Fatal error", e);
            }
            // swallow
            tests = m;
        }
        return tests;
    }


    public static Collection<String> tests() {
        return getTests().keySet();
    }

    public static TestInfo getInfo(String name) {
        TestInfo info = getTests().get(name);
        if (info == null) {
            throw new IllegalArgumentException("Unable to find info for test: " + name);
        }
        return info;
    }
}
