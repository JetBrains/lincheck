/*
 * Copyright (c) 2005, 2014, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.jcstress.infra.runners;

import org.openjdk.jcstress.infra.Status;
import org.openjdk.jcstress.infra.collectors.TestResult;
import org.openjdk.jcstress.util.Counter;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Basic runner for concurrency tests.
 *
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
public abstract class Runner<R> {
    protected static final int MIN_TIMEOUT_MS = 30*1000;

    protected final Control control;
    protected final ForkedTestConfig config;
    protected volatile boolean forceExit;

    public Runner(ForkedTestConfig config) {
        this.control = new Control();
        this.config = config;
    }

    /**
     * Run the test.
     * This method blocks until test is complete
     */
    public TestResult run() {
        Counter<R> result = new Counter<>();

        try {
            sanityCheck(result);
        } catch (Throwable e) {
            if (isAPIMismatchException(e)) {
                return dumpFailure(Status.API_MISMATCH, "Test sanity check failed, skipping", e);
            } else {
                return dumpFailure(Status.CHECK_TEST_ERROR, "Check test failed", e);
            }
        }

        ArrayList<CounterThread<R>> workers = internalRun();

        long startTime = System.nanoTime();
        do {
            ArrayList<CounterThread<R>> leftovers = new ArrayList<>();
            for (CounterThread<R> t : workers) {
                try {
                    t.join(1000);

                    if (t.throwable() != null) {
                        return dumpFailure(Status.TEST_ERROR, "Unrecoverable error while running", t.throwable());
                    }
                    Counter<R> res = t.result();
                    if (res != null) {
                        result.merge(res);
                    } else {
                        leftovers.add(t);
                    }
                } catch (InterruptedException e) {
                    return dumpFailure(Status.TEST_ERROR, "Unrecoverable error while running", e.getCause());
                }
            }

            long timeSpent = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            if (timeSpent > Math.max(10 * config.time, MIN_TIMEOUT_MS)) {
                forceExit = true;
                return dumpFailure(Status.TIMEOUT_ERROR, "Timeout waiting for tasks to complete: " + timeSpent + " ms");
            }

            workers = leftovers;
        } while (!workers.isEmpty());

        return dump(result);
    }

    private static boolean isAPIMismatchException(Throwable e) {
        while (e != null) {
            if (e instanceof ClassFormatError ||
                    e instanceof NoClassDefFoundError ||
                    e instanceof NoSuchMethodError ||
                    e instanceof NoSuchFieldError ||
                    e instanceof UnsupportedOperationException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    protected TestResult dumpFailure(Status status, String message) {
        TestResult r = new TestResult(status);
        r.addMessage(message);
        return r;
    }

    protected TestResult dumpFailure(Status status, String message, Throwable aux) {
        TestResult r = new TestResult(status);
        r.addMessage(message);
        r.addMessages(aux);
        return r;
    }

    protected TestResult dump(Counter<R> cnt) {
        TestResult r = new TestResult(Status.NORMAL);
        for (R e : cnt.elementSet()) {
             r.addState(String.valueOf(e), cnt.count(e));
        }
        return r;
    }

    public boolean forceExit() {
        return forceExit;
    }

    public abstract void sanityCheck(Counter<R> counter) throws Throwable;

    public abstract ArrayList<CounterThread<R>> internalRun();

}
