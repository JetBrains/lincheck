package com.github.lock.free.queue;

/*
 * #%L
 * libtest
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


import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Daneel Yaitskov
 */
public class LockFreeQueue<T> implements SimpleQueue<T> {

    // never empty
    private final AtomicLong length = new AtomicLong(1L);
    private final Node stub = new Node(null);
    private final AtomicReference<Node<T>> head = new AtomicReference<Node<T>>(stub);
    private final AtomicReference<Node<T>> tail = new AtomicReference<Node<T>>(stub);

    @Override
    public void add(T x) {
        addNode(new Node<T>(x));
        length.incrementAndGet();
    }

    @Override
    public T takeOrNull() {
        while (true) {
            long l = length.get();
            if (l == 1) {
                return null;
            }
            if (length.compareAndSet(l, l - 1)) {
                break;
            }
        }
        while (true) {
            Node<T> r = head.get();
            if (r == null) {
                throw new IllegalStateException("null head");
            }
            if (r.next.get() == null) {
                length.incrementAndGet();
                return null;
            }
            if (head.compareAndSet(r, r.next.get())) {
                if (r == stub) {
                    stub.next.set(null);
                    addNode(stub);
                } else {
                    return r.ref;
                }
            }
        }
    }

    private void addNode(Node<T> n) {
        Node<T> t;
        while (true) {
            t = tail.get();
            if (tail.compareAndSet(t, n)) {
                break;
            }
        }
        if (t.next.compareAndSet(null, n)) {
            return;
        }
        throw new IllegalStateException("bad tail next");
    }
}