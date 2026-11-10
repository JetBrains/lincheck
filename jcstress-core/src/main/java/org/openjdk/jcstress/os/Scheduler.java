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
package org.openjdk.jcstress.os;

import org.openjdk.jcstress.os.topology.Topology;

import java.util.*;

public class Scheduler {

    private boolean debug;

    private final BitSet availableCPUs;
    private final int maxUse;
    private final Topology topology;
    private final BitSet availableCores;
    private int currentUse;
    private final NodeRecord[] freeMapNode;

    public Scheduler(Topology t, int max) {
        topology = t;
        maxUse = max;
        availableCPUs = new BitSet(topology.totalThreads());
        availableCPUs.set(0, topology.totalThreads());
        availableCores = new BitSet(topology.totalCores());
        availableCores.set(0, topology.totalCores());
        freeMapNode = new NodeRecord[topology.nodesPerSystem()];
        for (int p = 0; p < freeMapNode.length; p++) {
            freeMapNode[p] = new NodeRecord(-1, -1);
        }
        recomputeFreeMaps();
    }

    public synchronized CPUMap tryAcquire(SchedulingClass scl) {
        if (currentUse + scl.numActors() > maxUse) {
            // Over the limit, break out.
            return null;
        }

        checkInvariants("Before acquire");

        CPUMap cpuMap;

        switch (scl.mode()) {
            case NONE:
                // Pretty much the same, but do not publish system map
                cpuMap = scheduleGlobalOrNone(scl, true);
                break;
            case GLOBAL:
                cpuMap = scheduleGlobalOrNone(scl, false);
                break;
            case LOCAL:
                cpuMap = scheduleLocal(scl);
                break;
            default:
                throw new IllegalStateException("Unhandled mode");
        }

        recomputeFreeMaps();

        checkInvariants("After acquire");

        return cpuMap;
    }

    private CPUMap scheduleLocal(SchedulingClass scl) {
        // Perform initial assignment of node groups to nodes.
        // Take the least busy nodes first, to guarantee the rest of
        // the scheduling code sees as many cores on those nodes as possible.

        int[] nodeGroupToNode = new int[scl.numNodes()];
        Arrays.fill(nodeGroupToNode, -1);

        int pIdx = 0;
        int[] coreGroupToNode = new int[scl.numCores()];
        for (int a = 0; a < scl.numActors(); a++) {
            int nodeGroup = scl.nodes[a];
            if (nodeGroup == -1) {
                throw new IllegalStateException("Bad actor map");
            }
            int p = nodeGroupToNode[nodeGroup];
            if (p == -1) {
                p = freeMapNode[pIdx++].id;
                nodeGroupToNode[nodeGroup] = p;
            }
            coreGroupToNode[scl.cores[a]] = p;
        }

        // Need to find enough cores and record them as allocated
        int[] coreGroupToCore = new int[scl.numCores()];
        Arrays.fill(coreGroupToCore, -1);

        for (int coreGroup = 0; coreGroup < scl.numCores(); coreGroup++) {
            // Find next core in the required node
            int wantNode = coreGroupToNode[coreGroup];

            int idx = 0;
            boolean found = false;
            while (true) {
                int core = availableCores.nextSetBit(idx);
                if (core < 0) break;

                if (topology.coreToNode(core) == wantNode) {
                    coreGroupToCore[coreGroup] = core;
                    availableCores.set(core, false);
                    found = true;
                    break;
                } else {
                    idx = core + 1;
                }
            }

            if (!found) {
                // Allocation failed, revert everything set in this round
                for (int c : coreGroupToCore) {
                    if (c != -1) {
                        availableCores.set(c, true);
                    }
                }
                return null;
            }
        }

        // Roll over actors and fill their core assignments
        int[] actorToThread = new int[scl.numActors()];
        Arrays.fill(actorToThread, -1);

        for (int aIdx = 0; aIdx < scl.numActors(); aIdx++) {
            int core = coreGroupToCore[scl.cores[aIdx]];

            for (int thread : topology.coreThreads(core)) {
                if (availableCPUs.get(thread)) {
                    availableCPUs.set(thread, false);
                    actorToThread[aIdx] = thread;
                    currentUse++;
                    break;
                }
            }
        }

        // Roll over the sibling threads and allow them too for system uses
        int[] system = new int[topology.totalThreads()];
        int systemCnt = 0;
        for (int core : coreGroupToCore) {
            for (int thread : topology.coreThreads(core)) {
                if (availableCPUs.get(thread)) {
                    availableCPUs.set(thread, false);
                    system[systemCnt++] = thread;
                    currentUse++;
                }
            }
        }

        for (int a : actorToThread) {
            if (a == -1) {
                throw new IllegalStateException("Scheduler error: allocation must have succeeded for " + scl +
                        ", was: " + Arrays.toString(actorToThread));
            }
        }

        int[] systemThreads = Arrays.copyOf(system, systemCnt);

        int[] threadToCore = new int[topology.totalThreads()];
        int[] threadToNode = new int[topology.totalThreads()];
        int[] threadToRealCPU = new int[topology.totalThreads()];
        Arrays.fill(threadToCore, -1);
        Arrays.fill(threadToNode, -1);
        Arrays.fill(threadToRealCPU, -1);

        for (int thread : actorToThread) {
            threadToNode[thread] = topology.threadToNode(thread);
            threadToCore[thread] = topology.threadToCore(thread);
            threadToRealCPU[thread] = topology.threadToRealCPU(thread);
        }
        for (int thread : systemThreads) {
            threadToNode[thread] = topology.threadToNode(thread);
            threadToCore[thread] = topology.threadToCore(thread);
            threadToRealCPU[thread] = topology.threadToRealCPU(thread);
        }

        int[] allocatedThreads = new int[actorToThread.length + systemThreads.length];
        System.arraycopy(actorToThread, 0, allocatedThreads, 0, actorToThread.length);
        System.arraycopy(systemThreads, 0, allocatedThreads, actorToThread.length, systemThreads.length);

        return new CPUMap(allocatedThreads, actorToThread, systemThreads,
                threadToNode, threadToCore, threadToRealCPU,
                topology.nodeType());
    }

    private CPUMap scheduleGlobalOrNone(SchedulingClass scl, boolean none) {
        // This ignores per-actor assignments completely.
        // It only allocates a separate core per actor, from the pool of all available cores.

        // Need to find enough cores and record them as allocated
        int[] actorToCore = new int[scl.numActors()];
        Arrays.fill(actorToCore, -1);

        for (int a = 0; a < scl.numActors(); a++) {
            int core = availableCores.nextSetBit(0);
            if (core >= 0) {
                actorToCore[a] = core;
                availableCores.set(core, false);
            } else {
                // Allocation failed, revert everything set in this round
                for (int c : actorToCore) {
                    if (c != -1) {
                        availableCores.set(c, true);
                    }
                }
                return null;
            }
        }

        // Take all affected cores as assignment
        int[] allocatedThreads = new int[topology.totalThreads()];
        int cnt = 0;

        for (int core : actorToCore) {
            for (int thread : topology.coreThreads(core)) {
                if (!availableCPUs.get(thread)) {
                    throw new IllegalStateException("Thread should be free");
                }
                availableCPUs.set(thread, false);
                allocatedThreads[cnt++] = thread;
                currentUse++;
            }
        }

        int[] actorThreads = new int[scl.numActors()];
        Arrays.fill(actorThreads, -1);

        allocatedThreads = Arrays.copyOf(allocatedThreads, cnt);
        int[] systemThreads;
        if (none) {
            // No assignments for system
            systemThreads = new int[0];
        } else {
            // All assignments go to system
            systemThreads = Arrays.copyOf(allocatedThreads, cnt);
        }

        int[] threadToCore = new int[topology.totalThreads()];
        int[] threadToNode = new int[topology.totalThreads()];
        int[] threadToRealCPU = new int[topology.totalThreads()];
        Arrays.fill(threadToCore, -1);
        Arrays.fill(threadToNode, -1);
        Arrays.fill(threadToRealCPU, -1);
        for (int thread : allocatedThreads) {
            threadToNode[thread] = topology.threadToNode(thread);
            threadToCore[thread] = topology.threadToCore(thread);
            threadToRealCPU[thread] = topology.threadToRealCPU(thread);
        }

        return new CPUMap(allocatedThreads, actorThreads, systemThreads,
                threadToNode, threadToCore, threadToRealCPU,
                topology.nodeType());
    }

    private void checkInvariants(String when) {
        if (!debug) return;

        for (int c = 0; c < topology.totalCores(); c++) {
            if (availableCores.get(c)) {
                for (int thread : topology.coreThreads(c)) {
                    if (!availableCPUs.get(thread)) {
                        throw new IllegalStateException(when + ": Available core should have all threads free");
                    }
                }
            }
        }

        int use = 0;
        for (int t = 0; t < topology.totalThreads(); t++) {
            if (!availableCPUs.get(t)) {
                use++;
                if (availableCores.get(topology.threadToCore(t))) {
                    throw new IllegalStateException(when + ": Thread taken from the core, core should not be available");
                }
            }
        }

        final int expected = currentUse;
        if (use != expected) {
            throw new IllegalStateException(when + ": CPU use counts are inconsistent, counter = " + expected + ", actually taken = " + use);
        }

        for (int n = 0; n < topology.nodesPerSystem(); n++) {
            int avail = 0;
            for (int core : topology.nodeCores(n)) {
                if (availableCores.get(core)) {
                    avail++;
                }
            }
            for (NodeRecord pr : freeMapNode) {
                if (pr.id == n && pr.avail != avail) {
                    throw new IllegalStateException(when + ": Node-core availability counts are inconsistent");
                }
            }
        }
    }

    public synchronized void release(CPUMap cpuMap) {
        checkInvariants("Before release");

        for (int c : cpuMap.allocatedThreads()) {
            availableCPUs.set(c, true);
            availableCores.set(topology.threadToCore(c), true);
            currentUse--;
        }

        recomputeFreeMaps();

        checkInvariants("After release");
    }

    private void recomputeFreeMaps() {
        for (int n = 0; n < topology.nodesPerSystem(); n++) {
            int avail = 0;
            for (int core : topology.nodeCores(n)) {
                if (availableCores.get(core)) {
                    avail++;
                }
            }
            freeMapNode[n].id = n;
            freeMapNode[n].avail = avail;
        }

        Arrays.sort(freeMapNode);
    }

    public int getCpus() {
        return currentUse;
    }

    private static class NodeRecord implements Comparable<NodeRecord> {
        int id;
        int avail;

        private NodeRecord(int id, int avail) {
            this.id = id;
            this.avail = avail;
        }

        @Override
        public int compareTo(NodeRecord other) {
            int compare = Integer.compare(other.avail, avail);
            if (compare != 0) {
                return compare;
            }
            return Integer.compare(id, other.id);
        }
    }

    void enableDebug() {
        debug = true;
    }

    public List<SchedulingClass> localAffinityFor(int actors, int threadLimit) {
        List<SchedulingClass> nodeCases = new ArrayList<>();

        // Assign node groups
        int[][] nodePerms = classPermutation(actors, topology.nodesPerSystem());
        for (int[] pp : nodePerms) {
            SchedulingClass scl = new SchedulingClass(AffinityMode.LOCAL, actors, topology.nodeType());
            for (int a = 0; a < actors; a++) {
                scl.setNode(a, pp[a]);
            }
            nodeCases.add(scl);
        }

        // Assign core groups
        List<SchedulingClass> coreCases = new ArrayList<>();
        for (SchedulingClass scl : nodeCases) {
            int numNodes = scl.numNodes();
            int[] nodeActors = scl.nodeActors();

            int[][][] nodeCoreAssignments = new int[numNodes][][];
            for (int p = 0; p < numNodes; p++) {
                int[][] perms = classPermutation(nodeActors[p], topology.coresPerNode());
                nodeCoreAssignments[p] = perms;
            }

            List<SchedulingClass> temp = new ArrayList<>();
            temp.add(scl);

            for (int p = 0; p < numNodes; p++) {
                List<SchedulingClass> newCases = new ArrayList<>();
                for (SchedulingClass tscl : temp) {
                    // Compute last assigned core class for other nodes
                    int coreShift = tscl.numCores();
                    if (coreShift < 0) coreShift = 0;

                    for (int[] coreClasses : nodeCoreAssignments[p]) {
                        SchedulingClass nscl = new SchedulingClass(tscl);
                        int ccIdx = 0;
                        for (int i = 0; i < actors; i++) {
                            if (scl.getNode(i) == p) {
                                int coreClass = coreClasses[ccIdx++] + coreShift;
                                nscl.setCore(i, coreClass);
                            }
                        }
                        newCases.add(nscl);
                    }
                }
                temp = newCases;
            }

            coreCases.addAll(temp);
        }

        // Assign thread groups: every thread gets its own group, by construction.
        // If there are not enough threads to satisfy same-core mapping, filter
        // out the actor map, leaving only exclusive-core configs.
        List<SchedulingClass> threadCases = new ArrayList<>();
        for (SchedulingClass scl : coreCases) {
            boolean enoughThreads = true;
            for (int ca : scl.coreActors()) {
                if (ca > topology.threadsPerCore()) {
                    enoughThreads = false;
                    break;
                }
            }

            if (scl.numActors() > threadLimit) {
                enoughThreads = false;
            }

            if (enoughThreads) {
                threadCases.add(scl);
            }
        }

        return threadCases;
    }

    private static int max(int[] perm) {
        int m = -1;
        for (int p2 : perm) {
            m = Math.max(m, p2);
        }
        return m;
    }

    static int[][] classPermutation(int num, int limit) {
        if (num < 1) {
            throw new IllegalArgumentException("Num should be at least 1: classPermutation(" + num + ", " + limit + ")");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("Limit should be at least 1");
        }

        // Base case
        if (num == 1) {
            return new int[][] { new int[] {0} };
        }

        int[][] oldPerms = classPermutation(num - 1, limit);

        // Compute the max class in old permutations, and the number
        // of permutations with maxed-out classes.
        int oldMax = Math.min(num - 1, limit) - 1;
        int newMax = oldMax + 1;

        int[][] newPerms = new int[oldPerms.length*(newMax+1)][];
        int nIdx = 0;

        // Copy all existing permutations with up to max variability
        for (int nm = 0; nm < newMax; nm++) {
            for (int[] p : oldPerms) {
                int m = max(p);
                if (m >= nm - 1) {
                    int[] np = Arrays.copyOf(p, p.length + 1);
                    np[p.length] = nm;
                    newPerms[nIdx++] = np;
                }
            }
        }

        // Copy all existing permutations with maxed out class
        for (int[] p : oldPerms) {
            int m = max(p);
            if (m == oldMax && m < limit - 1) {
                int[] np = Arrays.copyOf(p, p.length + 1);
                np[p.length] = newMax;
                newPerms[nIdx++] = np;
            }
        }

        return Arrays.copyOf(newPerms, nIdx);
    }

    List<SchedulingClass> globalAffinityFor(int threads, int threadLimit) {
        return noneOrGlobalAffinityFor(AffinityMode.GLOBAL, threads, threadLimit);
    }

    List<SchedulingClass> noneAffinityFor(int threads, int threadLimit) {
        return noneOrGlobalAffinityFor(AffinityMode.NONE, threads, threadLimit);
    }

    private List<SchedulingClass> noneOrGlobalAffinityFor(AffinityMode mode, int threads, int threadLimit) {
        if (threads > topology.totalCores()) {
            return Collections.emptyList();
        }
        if (threads > threadLimit) {
            return Collections.emptyList();
        }
        SchedulingClass scl = new SchedulingClass(mode, threads, topology.nodeType());
        for (int t = 0; t < threads; t++) {
            scl.setNode(t, -1);
            scl.setCore(t, -1);
        }
        return Collections.singletonList(scl);
    }

    @SuppressWarnings("fallthrough")
    public List<SchedulingClass> scheduleClasses(int actorThreads, int threadLimit, AffinityMode mode) {
        switch (mode) {
            case LOCAL:
                if (topology.trustworthy() && OSSupport.affinitySupportAvailable()) {
                    return localAffinityFor(actorThreads, threadLimit);
                }
            case GLOBAL:
                if (topology.trustworthy() && OSSupport.taskSetAvailable()) {
                    return globalAffinityFor(actorThreads, threadLimit);
                }
            case NONE:
                return noneAffinityFor(actorThreads, threadLimit);
            default:
                throw new IllegalStateException("Unhandled affinity mode: " + mode);
        }
    }
}
