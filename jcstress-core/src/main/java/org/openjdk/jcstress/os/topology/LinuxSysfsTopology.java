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

import org.openjdk.jcstress.os.NodeType;
import org.openjdk.jcstress.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class LinuxSysfsTopology extends AbstractTopology {

    private final Path cpuRoot;
    private final Path nodeRoot;
    private boolean numaMode;

    private int readInt(Path path) throws IOException, TopologyParseException {
        List<String> lines = Files.readAllLines(path);
        if (lines.size() > 0) {
            return Integer.parseInt(lines.get(0));
        } else {
            throw new TopologyParseException("Cannot read " + path);
        }
    }

    private List<Integer> readList(Path path) throws IOException, TopologyParseException {
        List<String> lines = Files.readAllLines(path);
        if (lines.size() > 0) {
            return StringUtils.decodeCpuList(lines.get(0));
        } else {
            throw new TopologyParseException("Cannot read " + path);
        }
    }

    public LinuxSysfsTopology() throws TopologyParseException {
        this(new File("/sys/devices/system/").toPath());
    }

    @SuppressWarnings("this-escape")
    public LinuxSysfsTopology(Path root) throws TopologyParseException {
        this.cpuRoot = root.resolve("cpu");
        this.nodeRoot = root.resolve("node");

        // Parse the number of available CPUs
        int cpuCount = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cpuRoot)) {
            for (Path d : ds) {
                if (!Files.isDirectory(d.resolve("topology"))) continue;

                String basename = d.getFileName().toString();
                if (basename.matches("cpu[0-9]+")) {
                    cpuCount++;
                }
            }
        } catch (Exception e) {
            // Nothing to do
        }

        // Parse the package groups
        List<List<Integer>> coreGroups = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cpuRoot)) {
            for (Path d : ds) {
                if (!Files.isDirectory(d.resolve("topology"))) continue;

                String basename = d.getFileName().toString();
                if (basename.matches("cpu[0-9]+")) {
                    List<Integer> list = readList(d.resolve("topology/package_cpus_list"));
                    coreGroups.add(list);
                }
            }
        } catch (Exception e) {
            // Nothing to do, fallback to package_id
            coreGroups.clear();
        }

        Collections.sort(coreGroups, (l1, l2) -> {
            for (int i = 0; i < Math.min(l1.size(), l2.size()); i++) {
                int c = Integer.compare(l1.get(i), l2.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(l1.size(), l2.size());
        });

        // Renumber packages here
        Map<List<Integer>, Integer> knownPackage = new HashMap<>();
        int packageCount = 0;
        for (List<Integer> cg : coreGroups) {
            if (!knownPackage.containsKey(cg)) {
                knownPackage.put(cg, packageCount++);
            }
        }

        SortedMap<Integer, Integer> cpuToNuma = new TreeMap<>();
        Set<Integer> numaNodes = new HashSet<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(nodeRoot)) {
            for (Path p : ds) {
                String basename = p.getFileName().toString();
                if (basename.matches("node[0-9]+")) {
                    int numaId = Integer.parseInt(basename.substring(4));
                    numaNodes.add(numaId);
                    for (Integer cpu : readList(p.resolve("cpulist"))) {
                        cpuToNuma.put(cpu, numaId);
                    }
                }
            }

            // Renumber numa nodes
            Map<Integer, Integer> renumberNuma = renumber(numaNodes, x -> x);
            cpuToNuma = remapValues(cpuToNuma, renumberNuma);
        } catch (Exception e) {
            // No NUMA support
            cpuToNuma.clear();
        }

        // Check if there is only one NUMA node. Disable NUMA support then.
        if (numaNodes.size() <= 1) {
            cpuToNuma.clear();
        }

        // There is more than 1 package. Disable NUMA support in favor of
        // per-package scheduling. There are some platforms that have multiple
        // sockets per "NUMA node" (see hwloc examples), but that seems to be
        // synthetic, and it would be more exhaustive to test per-package.
        if (packageCount > 1) {
            cpuToNuma.clear();
        }

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cpuRoot)) {
            boolean found = false;
            numaMode = false;

            for (Path d : ds) {
                if (!Files.isDirectory(d.resolve("topology"))) continue;

                String basename = d.getFileName().toString();
                if (basename.matches("cpu[0-9]+")) {
                    int threadId = Integer.parseInt(basename.substring(3));
                    int coreId = readInt(d.resolve("topology/core_id"));
                    int nodeId = readInt(d.resolve("topology/physical_package_id"));
                    if (cpuToNuma.containsKey(threadId)) {
                        // Prefer NUMA ID as node
                        nodeId = cpuToNuma.get(threadId);
                        numaMode = true;
                    } else {
                        if (numaMode) {
                            // Already have NUMA enabled, cannot fall back
                            throw new TopologyParseException("Thread " + threadId + " not found in NUMA list");
                        }
                        if (nodeId == -1) {
                            List<Integer> list = readList(d.resolve("topology/package_cpus_list"));
                            if (!knownPackage.containsKey(list)) {
                                throw new TopologyParseException("Cannot figure out package ID");
                            }
                            nodeId = knownPackage.get(list);
                        }
                    }
                    add(nodeId, nodeId*cpuCount + coreId, threadId);
                    found = true;
                }
            }
            if (!found) {
                throw new TopologyParseException("No CPUs found");
            }
        } catch (Exception e) {
            throw new TopologyParseException(e);
        }

        renumberAll();
        finish();
    }

    public void printStatus(PrintStream pw) {
        pw.println("  Linux, using " + cpuRoot + ", " + nodeRoot);
        super.printStatus(pw);
    }

    @Override
    public boolean trustworthy() {
        return true;
    }

    @Override
    public NodeType nodeType() {
        return numaMode ? NodeType.NUMA : NodeType.PACKAGE;
    }

}
