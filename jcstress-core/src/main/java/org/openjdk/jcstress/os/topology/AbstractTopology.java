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
import org.openjdk.jcstress.util.Multimap;
import org.openjdk.jcstress.util.TreesetMultimap;

import java.io.PrintStream;
import java.util.*;
import java.util.function.Function;

public abstract class AbstractTopology implements Topology {

    private SortedSet<Integer> nodes   = new TreeSet<>();
    private SortedSet<Integer> cores   = new TreeSet<>();
    private SortedSet<Integer> threads = new TreeSet<>();

    private SortedMap<Integer, Integer> threadToNode = new TreeMap<>();
    private SortedMap<Integer, Integer> threadToCore = new TreeMap<>();
    private SortedMap<Integer, Integer> coreToNode   = new TreeMap<>();

    private Multimap<Integer, Integer> coreToThread = new TreesetMultimap<>();
    private Multimap<Integer, Integer> nodeToCore   = new TreesetMultimap<>();

    private SortedMap<Integer, Integer> threadToRealCPU = new TreeMap<>();

    private int nodesPerSystem = -1;
    private int coresPerNode = -1;
    private int threadsPerCore = -1;

    private boolean finished;

    protected void add(int nodeId, int coreId, int threadId) throws TopologyParseException {
        String triplet = "N" + nodeId + ", C" + coreId + ", T" + threadId;

        if (nodeId == -1) {
            throw new TopologyParseException("Node is not initialized: " + triplet);
        }

        if (coreId == -1) {
            throw new TopologyParseException("Core is not initialized: " + triplet);
        }

        if (threadId == -1) {
            throw new TopologyParseException("Thread is not initialized: " + triplet);
        }

        nodes.add(nodeId);
        cores.add(coreId);
        threadToRealCPU.put(threadId, threadId);

        if (!threads.add(threadId)) {
            throw new TopologyParseException("Duplicate thread ID: " + triplet);
        }

        if (coreToNode.containsKey(coreId)) {
            Integer ex = coreToNode.get(coreId);
            if (!ex.equals(nodeId)) {
                throw new TopologyParseException("Core belongs to different nodes: " + triplet + ", " + ex);
            }
        } else {
            coreToNode.put(coreId, nodeId);
        }

        if (threadToNode.containsKey(threadId)) {
            Integer ex = threadToNode.get(threadId);
            if (!ex.equals(nodeId)) {
                throw new TopologyParseException("Thread belongs to different nodes: " + triplet + ", " + ex);
            }
        } else {
            threadToNode.put(threadId, nodeId);
        }

        if (threadToCore.containsKey(threadId)) {
            Integer ex = threadToCore.get(threadId);
            if (!ex.equals(coreId)) {
                throw new TopologyParseException("Thread belongs to different cores: " + triplet + ", " + ex);
            }
        } else {
            threadToCore.put(threadId, coreId);
        }

        nodeToCore.put(nodeId, coreId);
        coreToThread.put(coreId, threadId);
    }

    protected static <K, V> Multimap<K, V> remapKeys(Multimap<K, V> src, Map<K, K> remap) {
        TreesetMultimap<K, V> dst = new TreesetMultimap<>();
        for (K k : src.keys()) {
            K nk = remap.get(k);
            for (V v : src.get(k)) {
                dst.put(nk, v);
            }
        }
        return dst;
    }

    protected static <K, V> Multimap<K, V> remapValues(Multimap<K, V> src, Map<V, V> remap) {
        TreesetMultimap<K, V> dst = new TreesetMultimap<>();
        for (K k : src.keys()) {
            for (V v : src.get(k)) {
                dst.put(k, remap.get(v));
            }
        }
        return dst;
    }

    protected static <K, V> SortedMap<K, V> remapValues(SortedMap<K, V> src, Map<V, V> remap) {
        SortedMap<K, V> dst = new TreeMap<>();
        for (K k : src.keySet()) {
            dst.put(k, remap.get(src.get(k)));
        }
        return dst;
    }

    protected static <K, V> SortedMap<K, V> remapKeys(SortedMap<K, V> src, Map<K, K> remap) {
        SortedMap<K, V> dst = new TreeMap<>();
        for (K k : src.keySet()) {
            dst.put(remap.get(k), src.get(k));
        }
        return dst;
    }

    protected static <K> Map<K, K> renumber(Set<K> src, Function<Integer, K> gen) {
        Map<K, K> dst = new HashMap<>();
        int nid = 0;
        for (K k : src) {
            dst.put(k, gen.apply(nid++));
        }
        return dst;
    }

    protected void renumberAll() {
        renumberNodes();
        renumberCores();
        renumberThreads();
    }

    private void renumberCores() {
        checkNotFinished();

        Map<Integer, Integer> renumber = renumber(cores, x -> x);

        cores = new TreeSet<>(renumber.values());
        coreToThread  = remapKeys(coreToThread, renumber);
        coreToNode = remapKeys(coreToNode, renumber);
        threadToCore  = remapValues(threadToCore, renumber);
        nodeToCore = remapValues(nodeToCore, renumber);
    }

    private void renumberThreads() {
        checkNotFinished();

        Map<Integer, Integer> renumber = renumber(threads, x -> x);
        for (Integer ot : threads) {
            threadToRealCPU.put(renumber.get(ot), ot);
        }

        threads = new TreeSet<>(renumber.values());
        coreToThread = remapValues(coreToThread, renumber);
        threadToCore = remapKeys(threadToCore, renumber);
        threadToNode = remapKeys(threadToNode, renumber);
    }

    private void renumberNodes() {
        checkNotFinished();

        Map<Integer, Integer> renumber = renumber(nodes, x -> x);

        nodes = new TreeSet<>(renumber.values());
        threadToNode = remapValues(threadToNode, renumber);
        coreToNode = remapValues(coreToNode, renumber);
        nodeToCore = remapKeys(nodeToCore, renumber);
    }

    protected void finish() throws TopologyParseException {
        checkNotFinished();

        if (nodes.first() != 0 || nodes.last() != nodes.size() - 1) {
            throw new TopologyParseException("Node IDs are not consecutive: " + nodes);
        }

        if (cores.first() != 0 || cores.last() != cores.size() - 1) {
            throw new TopologyParseException("Core IDs are not consecutive: " + cores);
        }

        if (threads.first() != 0 || threads.last() != threads.size() - 1) {
            throw new TopologyParseException("Thread IDs are not consecutive: " + threads);
        }

        nodesPerSystem = nodes.size();

        for (Integer p : nodeToCore.keys()) {
            int size = nodeToCore.get(p).size();
            if (coresPerNode == -1) {
                coresPerNode = size;
            } else {
                coresPerNode = Math.min(coresPerNode, size);
            }
        }

        for (Integer c : coreToThread.keys()) {
            int size = coreToThread.get(c).size();
            if (threadsPerCore == -1) {
                threadsPerCore = size;
            } else {
                threadsPerCore = Math.min(threadsPerCore, size);
            }
        }

        finished = true;
    }

    private void checkFinished() {
        if (!finished) {
            throw new IllegalStateException("Should be finished first");
        }
    }

    private void checkNotFinished() {
        if (finished) {
            throw new IllegalStateException("Should not be finished yet");
        }
    }

    @Override
    public void printStatus(PrintStream pw) {
        checkFinished();
        pw.printf("  %d %s%s, %d core%s per %s, %d thread%s per core%n",
                nodesPerSystem,
                nodeType().desc(),
                nodesPerSystem > 1 ? "s" : "",
                coresPerNode,
                coresPerNode > 1 ? "s" : "",
                nodeType().desc(),
                threadsPerCore,
                threadsPerCore > 1 ? "s" : "");
        pw.println();
        pw.println("  CPU topology:");
        for (Integer pack : nodes) {
            for (Integer core : nodeToCore.get(pack)) {
                for (Integer thread : coreToThread.get(core)) {
                    pw.printf("    CPU %s: %s #%d, core #%d, thread #%d%n",
                            String.format("%3s", "#" + threadToRealCPU.get(thread)),
                            nodeType().desc(),
                            pack, core, thread);
                }
            }
        }
    }

    public int threadsPerCore() {
        checkFinished();
        return threadsPerCore;
    }

    public int coresPerNode() {
        checkFinished();
        return coresPerNode;
    }

    public int nodesPerSystem() {
        checkFinished();
        return nodesPerSystem;
    }

    public int totalThreads() {
        checkFinished();
        return threads.size();
    }

    @Override
    public int totalCores() {
        checkFinished();
        return cores.size();
    }

    @Override
    public Collection<Integer> coreThreads(int core) {
        checkFinished();
        return coreToThread.get(core);
    }

    @Override
    public Collection<Integer> nodeCores(int nodeId) {
        checkFinished();
        return nodeToCore.get(nodeId);
    }

    @Override
    public int coreToNode(int coreId) {
        checkFinished();
        return coreToNode.get(coreId);
    }

    @Override
    public int threadToNode(int thread) {
        checkFinished();
        Integer v = threadToNode.get(thread);
        if (v == null) {
            throw new IllegalArgumentException("Cannot find node mapping for thread " + thread);
        }
        return v;
    }

    @Override
    public int threadToCore(int thread) {
        checkFinished();
        Integer v = threadToCore.get(thread);
        if (v == null) {
            throw new IllegalArgumentException("Cannot find core mapping for thread " + thread);
        }
        return v;
    }

    @Override
    public int threadToRealCPU(int thread) {
        checkFinished();
        Integer v = threadToRealCPU.get(thread);
        if (v == null) {
            throw new IllegalArgumentException("Cannot find real CPU mapping for thread " + thread);
        }
        return v;
    }

    @Override
    public NodeType nodeType() {
        return NodeType.PACKAGE;
    }
}
