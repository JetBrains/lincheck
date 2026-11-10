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

import java.util.*;

public class Trace {
    private final List<Op> ops;

    public Trace() {
        ops = new ArrayList<>();
    }

    public Trace(Collection<Op> extOps) {
        ops = new ArrayList<>(extOps);
    }

    public List<Op> ops() {
        return ops;
    }

    public Trace pushHead(Op op) {
        Trace nT = new Trace();
        nT.ops.add(op);
        nT.ops.addAll(ops);
        return nT;
    }

    public Trace pushTail(Op op) {
        Trace nT = new Trace();
        nT.ops.addAll(ops);
        nT.ops.add(op);
        return nT;
    }

    @Override
    public String toString() {
        return "{" + ops + '}';
    }

    public int getLength() {
        return ops.size();
    }

    public TraceResult interpret() {
        Map<Integer, Value> values = new HashMap<>();
        for (Op op : ops) {
            values.put(op.getVarId(), Value.defaultOne());
        }

        SortedMap<Result, Value> resValues = new TreeMap<>();

        for (Op op : ops) {
            switch (op.getType()) {
                case LOAD:
                    Value v = values.get(op.getVarId());
                    resValues.put(op.getResult(), v);
                    break;
                case STORE:
                    values.put(op.getVarId(), op.getValue());
                    break;
                default:
                    throw new IllegalStateException();
            }
        }

        return new TraceResult(resValues, values);
    }

    public Trace removeFirst() {
        Trace nT = new Trace();
        nT.ops.addAll(ops);
        nT.ops.remove(0);
        return nT;
    }

    public boolean hasLoads() {
        for (Op op : ops) {
            if (op.isLoad()) return true;
        }
        return false;
    }

    public boolean hasStores() {
        for (Op op : ops) {
            if (op.isStore()) return true;
        }
        return false;
    }

    public int loadCount() {
        int count = 0;
        for (Op op : ops) {
            if (op.isLoad()) count++;
        }
        return count;
    }

    public int storeCount() {
        int count = 0;
        for (Op op : ops) {
            if (op.isStore()) count++;
        }
        return count;
    }

    public String id() {
        StringBuilder sb = new StringBuilder();
        for (Op op : ops) {
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
        return sb.toString();
    }

    public String loadStoreSeq() {
        StringBuilder sb = new StringBuilder();
        for (Op op : ops) {
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
        }
        return sb.toString();
    }

}
