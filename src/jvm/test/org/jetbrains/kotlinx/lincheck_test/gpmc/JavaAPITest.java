/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc;

import org.junit.Test;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jetbrains.kotlinx.lincheck.Lincheck.runConcurrentTest;
import static org.junit.Assert.assertFalse;

public class JavaAPITest {
    @Test
    public void test() {
        runConcurrentTest(JavaAPITest::testImpl);
    }

    @Test
    public void test2() {
        runConcurrentTest(() -> {
            ConcurrentLinkedDeque<Integer> deque = new ConcurrentLinkedDeque<>();
            int[] results = new int[2];

            deque.addLast(1);

            Thread t1 = new Thread(() -> {
                results[0] = deque.pollFirst();
            });
            Thread t2 = new Thread(() -> {
                deque.addFirst(0);
                results[1] = deque.peekLast();
            });

            t1.start();
            t2.start();
            try {
                t1.join();
                t2.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            assertFalse(results[0] == 1 && results[1] == 1);
//            fail();
        });
    }

    public static void testImpl() {
        ConcurrentLinkedDeque<Integer> deque = new ConcurrentLinkedDeque<>();
        AtomicInteger r1 = new AtomicInteger(-1);
        AtomicInteger r2 = new AtomicInteger(-1);

        deque.addLast(1);

        Thread t1 = new Thread(() -> {
            // TODO: check the output -- the interleaving here is likely incorrect.
            r1.set(deque.pollFirst());
        });
        Thread t2 = new Thread(() -> {
            deque.addFirst(0);
            r2.set(deque.peekLast());
        });

        t1.start();
        t2.start();
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertFalse(r1.get() == 1 && r2.get() == 1);
    }
}
