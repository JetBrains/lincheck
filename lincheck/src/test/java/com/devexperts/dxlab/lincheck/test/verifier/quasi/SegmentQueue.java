package com.devexperts.dxlab.lincheck.test.verifier.quasi;

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * k-quasi linearizable queue, that scatters the contention both for @dequeue and @enqueue operations
 *
 * SegmentedQueue maintains a linked list of segments, each segment is an array of nodes in the size of QF
 * Each enqueuer(dequeuer) iterates over the last(first) segment in the linked list, attempting to find an empty(non-empty) cell
 */
public class SegmentQueue<T> {

    private int k;
    private final AtomicReference<KSegment> head = new AtomicReference<>();
    private final AtomicReference<KSegment> tail = new AtomicReference<>();

    private final Random random = new Random();

    public SegmentQueue(int k) {
        this.k = k;
        KSegment empty = new KSegment(null);
        head.set(empty);
        tail.set(empty);
    }

    private class KSegment {
        final AtomicReference<T>[] segment;
        AtomicReference<KSegment> next = new AtomicReference<>();

        private KSegment(KSegment next) {
            this.segment = new AtomicReference[k + 1];
            for (int i = 0; i <= k; i++) {
                segment[i] = new AtomicReference<>(null);
            }
            this.next.set(next);
        }
    }

    public void enqueue(T x) {
        while (true) {
            KSegment curTail = tail.get();
            // iterate through the tail segment to find an empty place
            int startIndex = Math.abs(ThreadLocalRandom.current().nextInt()) % k;
            for (int i = 0; i <= k; i++) {
                if (curTail.segment[(i + startIndex) % k].compareAndSet(null, x)) {
                    return;
                }
            }
            // no empty place was found -> add new segment
            if (curTail == tail.get()) {
                KSegment newTail = new KSegment(null);
                if (curTail.next.compareAndSet(null, newTail)) {
                    tail.compareAndSet(curTail, newTail);
                } else {
                    tail.compareAndSet(curTail, curTail.next.get());
                }
            }
        }
    }

    public T dequeue() {
        while (true) {
            KSegment curHead = head.get();
            //iterate through the head segment to find a non-null element
            int startIndex = Math.abs(ThreadLocalRandom.current().nextInt()) % k;
            for (int i = 0; i <= k; i++) {
                T old_value = curHead.segment[(i + startIndex) % k].get();
                if (old_value == null) {
                    continue;
                }
                if (curHead.segment[(i + startIndex) % k].compareAndSet(old_value, null)) {
                    return old_value;
                }
            }
            // all elements were dequeued -> we can remove this segment, if it's not a single one
            KSegment curTail = tail.get();
            if (curHead != curTail) {
                head.compareAndSet(curHead, curHead.next.get());
            } else {
                if (curTail.next.get() != null) {
                    tail.compareAndSet(curTail, curTail.next.get());
                } else {
                    return null;
                }
            }
        }
    }
}