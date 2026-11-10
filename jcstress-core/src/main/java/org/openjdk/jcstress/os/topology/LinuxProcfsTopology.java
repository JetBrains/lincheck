/*
 * Copyright (c) 2021, Red Hat, Inc. All rights reserved.
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
package org.openjdk.jcstress.os.topology;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinuxProcfsTopology extends AbstractTopology {

    private final String file;

    public LinuxProcfsTopology() throws TopologyParseException {
        this("/proc/cpuinfo");
    }

    @SuppressWarnings("this-escape")
    public LinuxProcfsTopology(String file) throws TopologyParseException {
        this.file = file;
        try {
            List<String> lines = Files.readAllLines(new File(file).toPath(), Charset.defaultCharset());

            Pattern processorPattern = Pattern.compile("^processor(\\s+):(\\s+)(\\d+)");
            Pattern packagePattern = Pattern.compile("^physical id(\\s+):(\\s+)(\\d+)");
            Pattern corePattern = Pattern.compile("^core id(\\s+):(\\s+)(\\d+)");

            int procId = -1;
            int packageId = -1;
            int coreId = -1;

            for (String line : lines) {
                Matcher idMatcher = processorPattern.matcher(line);
                if (idMatcher.matches()) {
                    if (procId != -1) {
                        add(packageId, coreId, procId);
                        procId = -1;
                        packageId = -1;
                        coreId = -1;
                    }
                    procId = Integer.parseInt(idMatcher.group(3));
                }

                Matcher packageMatcher = packagePattern.matcher(line);
                if (packageMatcher.matches()) {
                    packageId = Integer.parseInt(packageMatcher.group(3));
                }

                Matcher coreMatcher = corePattern.matcher(line);
                if (coreMatcher.matches()) {
                    coreId = Integer.parseInt(coreMatcher.group(3));
                }
            }

            if (procId != -1) {
                add(packageId, coreId, procId);
            }
        } catch (IOException e) {
            throw new TopologyParseException(e);
        }

        finish();
    }

    public void printStatus(PrintStream pw) {
        pw.println("  Linux procfs, using " + file);
        super.printStatus(pw);
    }

    @Override
    public boolean trustworthy() {
        return true;
    }
}
