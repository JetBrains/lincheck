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

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

class Phase {
    private static final int TYPES = 2;

    private final int count;
    private final long cases;
    private final int threads;
    private final int vars;

    public Phase(int count, int vars, int threads) {
        this.count = count;
        this.vars = vars;
        this.threads = threads;

        // Long-accurate Math.pow
        long cases = 1L;
        for (int c = 0; c < count; c++) {
            cases *= vars * threads * TYPES;
        }
        this.cases = cases;
    }

    public List<MultiThread> run() {
        System.out.printf("Generating test cases for %d ops, %d variable(s), %d thread(s)... %10d testcases to filter... ",
                count, vars, threads, cases);

        Set<String> canonicalIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

        List<MultiThread> list = LongStream.range(0, cases)
                .parallel()
                .filter(this::filterTrace)
                .mapToObj(ticket -> {
                    Multimap<Integer, Op> threadOps = generateTrace(ticket);
                    List<Trace> threads = threadOps.valueGroups().stream()
                            .map(Trace::new)
                            .collect(Collectors.toList());
                    return new MultiThread(threads);
                })
                .filter(MultiThread::hasNoSingleLoadThreads)        // single load threads blow up the test case count
                .filter(MultiThread::hasNoIntraThreadPairs)         // no load-stores within a single thread
                .filter(mt -> {                                     // pass only a canonical order of arguments
                    String cid = mt.canonicalId();
                    return mt.id().equals(cid) && canonicalIds.add(cid);
                })
                .collect(Collectors.toList());

        System.out.printf("%5d interesting testcases%n", list.size());
        return list;
    }

    private Multimap<Integer, Op> generateTrace(long ticket) {
        Multimap<Integer, Op> result = new HashMultimap<>();
        int valId = Value.initial();
        int resId = 1;

        long t = ticket;
        for (int c = 0; c < count; c++) {
            int thread = (int) (t % threads);
            t /= threads;

            int type = (int) (t % TYPES);
            t /= TYPES;

            int varId = (int) (t % vars);
            t /= vars;

            Op op;
            switch (type) {
                case 0:
                    Result res = new Result(resId++);
                    op = new Op.LoadOp(varId + 1, res);
                    break;
                case 1:
                    Value value = Value.newOne(valId++);
                    op = new Op.StoreOp(varId + 1, value);
                    break;
                default:
                    throw new IllegalStateException();
            }

            result.put(thread, op);
        }

        return result;
    }

    private boolean filterTrace(long ticket) {
        long usedThreadsMask = 0L;
        long usedVarsMask = 0L;
        long storeMask = 0L;

        long t = ticket;
        for (int c = 0; c < count; c++) {
            int thread = (int) (t % threads);
            t /= threads;

            usedThreadsMask |= (1 << thread);

            int type = (int) (t % TYPES);
            t /= TYPES;

            int varId = (int) (t % vars);
            t /= vars;

            usedVarsMask |= (1 << varId);
            switch (type) {
                case 0:
                    break;
                case 1:
                    storeMask |= (1 << varId);
                    break;
                default:
                    throw new IllegalStateException();
            }
        }

        if (t > 0) {
            throw new IllegalStateException("Unclaimed ticket bits: " + t);
        }

        // Has loads without stores, bail
        if (usedVarsMask != storeMask) return false;

        // All vars are used? If not, bail
        if (usedVarsMask != ((1 << vars) - 1)) return false;

        // All threads are used? If not, bail
        if (usedThreadsMask != ((1 << threads) - 1)) return false;

        return true;
    }

}
