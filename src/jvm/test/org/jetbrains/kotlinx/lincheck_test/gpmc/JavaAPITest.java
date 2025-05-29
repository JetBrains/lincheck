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

import java.util.concurrent.ConcurrentLinkedDeque;

import org.jetbrains.lincheck.Lincheck;
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError;
import org.junit.Test;

import static org.junit.Assert.assertFalse;

public class JavaAPITest {

    @Test(expected = LincheckAssertionError.class)
    public void testMethodReference() {
        Lincheck.runConcurrentTest(INVOCATIONS, JavaAPITest::testImpl);
    }

    public static void testImpl() {
        int[] results = new int[2];

        ConcurrentLinkedDeque<Integer> deque = new ConcurrentLinkedDeque<>();
        deque.addLast(1);

        Thread t1 = new Thread(() -> {
            // TODO: check the output -- the interleaving here is likely incorrect.
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
    }

    @Test(expected = LincheckAssertionError.class)
    public void testLambda() {
        Lincheck.runConcurrentTest(INVOCATIONS, () -> {
            int[] results = new int[2];

            ConcurrentLinkedDeque<Integer> deque = new ConcurrentLinkedDeque<>();
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
        });
    }

    static final int INVOCATIONS = 50_000;

}
