/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.jcstress.util;

import org.openjdk.jcstress.infra.Copyable;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Computes the histogram on arbitrary results.
 * Watch for optimized contracts for the methods.
 *
 * @param <R> result type.
 */
public final class Counter<R> implements Serializable {

    /*
     * Implementation notes: the requirements for Counter are very relaxed,
     * and therefore this implementation cuts around the edges heavily:
     *   - we do not allow nulls!
     *   - we do not support removals, therefore cleanup strategy is not
     *     required to implement;
     *   - values are always longs, saving us additional dereferences and
     *     boxing/unboxing;
     *   - resizes are infrequent, since most of the time the case count is
     *     minuscule;
     */

    private static final int RECIPROCAL_LOAD_FACTOR = 10;
    private static final int INITIAL_CAPACITY = 64;

    private Object[] keys;
    private long[] counts;
    private int length;
    private int keyCount;

    public Counter() {
        init();
    }

    private void init() {
        length = INITIAL_CAPACITY;

        @SuppressWarnings("unchecked")
        R[] table = (R[]) new Object[length];
        this.keys = table;
        this.counts = new long[length];
        this.keyCount = 0;
    }

    public Counter(DataInputStream dis) throws IOException {
        init();
        int len = dis.readInt();
        for (int c = 0; c < len; c++) {
            @SuppressWarnings("unchecked")
            R key = (R) dis.readUTF();
            long count = dis.readLong();
            record(key, count);
        }
    }

    public void write(DataOutputStream dos) throws IOException {
        dos.writeInt(keyCount);
        for (int c = 0; c < keys.length; c++) {
            if (keys[c] != null) {
                dos.writeUTF(keys[c].toString());
                dos.writeLong(counts[c]);
            }
        }
    }

    /**
     * Records the result with given occurrences count.
     * The result can mutate after the call is finished.
     *
     * @param result result to record
     * @param count number of occurrences to record
     */
    public final void record(R result, long count) {
        int idx = result.hashCode() & (length - 1);

        Object k = keys[idx];
        while (k != null) {
            // hit the bucket, update and exit
            if (k.equals(result)) {
                counts[idx] += count;
                return;
            }

            // trying the next bucket
            idx = (idx + 1) & (length - 1);
            k = keys[idx];
        }

        // whoops, map is overloaded, resize to make up
        // the space, try again (succeeding), and exit;
        // we might want to resize early
        if (keyCount * RECIPROCAL_LOAD_FACTOR > length) {
            resize();
            record(result, count);
            return;
        }

        // completely new key, insert, and exit
        keyCount++;
        keys[idx] = copyOf(result);
        counts[idx] = count;
    }

    /**
     * Merge another counter data into this counter
     * @param other counter
     */
    public final void merge(Counter<R> other) {
        for (R key : other.elementSet()) {
            record(key, other.count(key));
        }
    }

    private void resize() {
        Object[] prevKeys = keys;
        long[] prevCounts = counts;

        // double so:
        int newLen = (length << 1);

        // allocate new stuff
        @SuppressWarnings("unchecked")
        R[] table = (R[]) new Object[newLen];
        keys = table;
        counts = new long[newLen];
        length = newLen;

        // rehash the entire map
        for (int kIdx = 0; kIdx < prevKeys.length; kIdx++) {
            Object k = prevKeys[kIdx];
            if (k != null) {
                int idx = k.hashCode() & (length - 1);
                while (keys[idx] != null) {
                    idx = (idx + 1) & (length - 1);
                }
                keys[idx] = k;
                counts[idx] = prevCounts[kIdx];
            }
        }
    }

    /**
     * Return the result count.
     *
     * @param result result to count
     */
    public final long count(R result) {
        int idx = result.hashCode() & ((length - 1));
        while (keys[idx] != null) {
            if (keys[idx].equals(result)) {
                return counts[idx];
            }
            idx = (idx + 1) & (length - 1);
        }
        return 0L;
    }

    private static <T> T copyOf(T result) {
        // Known immutable
        if (result instanceof String) {
            return result;
        }

        // Special class that knows how to copy itself
        if (result instanceof Copyable) {
            Object copy = ((Copyable) result).copy();
            @SuppressWarnings("unchecked")
            final T tCopy = (T) copy;
            return tCopy;
        }

        // Generic copying...
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(result);
            oos.close();

            ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
            ObjectInputStream ois = new ObjectInputStream(bis);

            @SuppressWarnings("unchecked")
            T t = (T)ois.readObject();

            return t;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Return the collection of accumulated unique results.
     * @return set
     */
    public final Collection<R> elementSet() {
        List<R> res = new ArrayList<>();
        for (Object k : keys) {
            if (k != null) {
                @SuppressWarnings("unchecked")
                R e = (R) k;
                res.add(e);
            }
        }
        return res;
    }

    public long totalCount() {
        long s = 0;
        for (long c : counts) {
            s += c;
        }
        return s;
    }

    public boolean isEmpty() {
        for (long c : counts) {
            if (c > 0) return false;
        }
        return true;
    }

}
