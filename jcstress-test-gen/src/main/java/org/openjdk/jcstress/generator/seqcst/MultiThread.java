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
package org.openjdk.jcstress.generator.seqcst;

import org.openjdk.jcstress.util.HashMultimap;
import org.openjdk.jcstress.util.Multimap;

import java.util.*;
import java.util.stream.Collectors;

public class MultiThread {
    private final List<Trace> threads;

    public MultiThread(Collection<Trace> ts) {
        this.threads = new ArrayList<>();
        for (Trace t : ts) {
            if (!t.ops().isEmpty())
                threads.add(t);
        }
    }

    /**
     * @return all executions from linearizing the thread operations.
     */
    public List<Trace> linearize() {
        if (threads.isEmpty()) {
            return Collections.singletonList(new Trace());
        }

        List<Trace> newTraces = new ArrayList<>();

        for (int t = 0; t < threads.size(); t++) {
            List<Trace> copy = new ArrayList<>();
            copy.addAll(threads);

            Trace cT = copy.get(t);
            if (cT.ops().isEmpty()) {
                copy.remove(t);
                newTraces.addAll(new MultiThread(copy).linearize());
            } else {
                Op op = cT.ops().get(0);
                copy.set(t, cT.removeFirst());

                if (cT.ops().isEmpty()) {
                    copy.remove(t);
                }

                for (Trace trace : new MultiThread(copy).linearize()) {
                    newTraces.add(trace.pushHead(op));
                }
            }
        }

        return newTraces;
    }

    @Override
    public String toString() {
        return "{" + threads + '}';
    }

    public String canonicalId() {
        // Try to canonicalize the test case.

        List<Trace> sorted = new ArrayList<>(threads);

        // Step 1. Sort by trace length, descending, then by internal structure.
        // This prepares us for more stable renumbering.

        Collections.sort(sorted, Comparator
                .comparing((Trace t) -> -t.loadStoreSeq().length())
                .thenComparing(Trace::loadStoreSeq));

        // Step 2. Renumber the variables.
        // This removes the structurally similar test cases.
        int varId = 1;
        Map<Integer, Integer> varMap = new HashMap<>();
        for (Trace trace : sorted) {
            for (Op op : trace.ops()) {
                Integer id = varMap.get(op.getVarId());
                if (id == null) {
                    id = varId++;
                    varMap.put(op.getVarId(), id);
                }
            }
        }

        List<Trace> renumbered = new ArrayList<>();
        for (Trace trace : sorted) {
            List<Op> newOps = new ArrayList<>();
            for (Op op : trace.ops()) {
                newOps.add(op.renumber(varMap.get(op.getVarId())));
            }
            renumbered.add(new Trace(newOps));
        }

        // Step 3. Sort again after renumbering.
        // Once renumbering happened, some traces might have changed,
        // we need to canonicalize their placement as well.

        Collections.sort(renumbered, Comparator.comparing(Trace::id));

        // Final step. Print out the ID
        return new MultiThread(renumbered).id();
    }

    public String id() {
        StringBuilder sb = new StringBuilder();
        for (Trace trace : threads) {
            for (Op op : trace.ops()) {
                switch (op.getType()) {
                    case LOAD:
                        sb.append("L");
                        break;
                    case STORE:
                        sb.append("S");
                        break;
                    default:
                        throw new IllegalStateException();
                }
                sb.append(op.getVarId());
                sb.append("_");
            }
            sb.append("_");
        }
        sb.delete(sb.length() - 2, sb.length());
        return sb.toString();
    }

    public boolean hasNoSingleLoadThreads() {
        for (Trace trace : threads) {
            if (trace.loadCount() == 1 && trace.storeCount() == 0) return false;
        }
        return true;
    }

    public boolean hasNoIntraThreadPairs() {
        BitSet global = new BitSet();
        BitSet touched = new BitSet();

        for (Trace trace : threads) {
            BitSet thisThreadTouched = new BitSet();
            for (Op op : trace.ops()) {
                int id = op.getVarId();
                if (touched.get(id)) {
                    global.set(id);
                } else {
                    thisThreadTouched.set(id);
                }
            }
            touched.or(thisThreadTouched);
        }
        return global.equals(touched);
    }

    public Set<Result> allResults() {
        return threads.stream()
                .flatMap(t -> t.ops().stream())
                .filter(Op::isLoad)
                .map(Op::getResult)
                .collect(Collectors.toSet());
    }

    public Set<Value> allValues() {
        return threads.stream()
                .flatMap(t -> t.ops().stream())
                .filter(Op::isStore)
                .map(Op::getValue)
                .collect(Collectors.toSet());
    }

    public Set<Integer> allVariables() {
        return threads.stream()
                .flatMap(t -> t.ops().stream())
                .map(Op::getVarId)
                .collect(Collectors.toSet());
    }

    /**
     * @return The set of outcomes where every load can see every store.
     */
    public Set<TraceResult> racyResults() {
        /*
           Step 1. Produce all possible results:
           (Results can see all writes
         */
        Set<Map<Result, Value>> allResults = new HashSet<>();
        allResults.add(new HashMap<>());

        for (Result r : allResults()) {
            Set<Map<Result, Value>> temp = new HashSet<>();

            for (Map<Result, Value> sub : allResults) {
                for (Value v : allValues()) {
                    Map<Result, Value> newMap = new HashMap<>(sub);
                    newMap.put(r, v);
                    temp.add(newMap);
                }

                {
                    Map<Result, Value> newMap = new HashMap<>(sub);
                    newMap.put(r, Value.defaultOne());
                    temp.add(newMap);
                }
            }

            allResults = temp;
        }

        /*
           Step 2. Produce all possible variable values:
         */
        Multimap<Integer, Value> possibleValues = new HashMultimap<>();
        for (Trace t : threads) {
            for (Op op : t.ops()) {
                if (op.isStore()) {
                    possibleValues.put(op.getVarId(), op.getValue());
                }
            }
        }

        Set<Map<Integer, Value>> allValues = new HashSet<>();
        allValues.add(new HashMap<>());

        for (Integer varId : possibleValues.keys()) {
            Set<Map<Integer, Value>> temp = new HashSet<>();

            for (Map<Integer, Value> m : allValues) {
                for (Value val : possibleValues.get(varId)) {
                    Map<Integer, Value> newMap = new HashMap<>(m);
                    newMap.put(varId, val);
                    temp.add(newMap);
                }
            }

            allValues = temp;
        }

        /*
           Step 3. Results, product of all possible results and values:
         */
        Set<TraceResult> answer = new HashSet<>();
        for (Map<Result, Value> results : allResults) {
            for (Map<Integer, Value> values : allValues) {
                answer.add(new TraceResult(results, values));
            }
        }

        return answer;
    }

    public int loadCount() {
        int r = 0;
        for (Trace trace : threads) {
            r += trace.loadCount();
        }
        return r;
    }

    public List<Trace> threads() {
        return threads;
    }
}
