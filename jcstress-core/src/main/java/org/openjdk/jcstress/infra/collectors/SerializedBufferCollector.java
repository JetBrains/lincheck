/*
 * Copyright (c) 2017, Red Hat Inc. All rights reserved.
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
package org.openjdk.jcstress.infra.collectors;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Accumulates test results in the internal queue, and uses a single thread
 * to call other collectors with the data from the queue. This allows to unblock
 * clients early.
 *
 * @author Aleksey Shipilev (shade@redhat.com)
 */
public class SerializedBufferCollector implements TestResultCollector {

    private final TestResultCollector sink;
    private final BlockingQueue<TestResult> results;
    private final Thread processor;
    private volatile boolean terminated;

    public SerializedBufferCollector(TestResultCollector dst) {
        sink = dst;
        results = new ArrayBlockingQueue<>(1024);
        processor = new Thread(this::work);
        processor.setName(SerializedBufferCollector.class.getName() + " processor thread");
        processor.setDaemon(true);
        processor.start();
    }

    public void close() {
        terminated = true;
        try {
            processor.join();
        } catch (InterruptedException e) {
            // do nothing
        }
    }

    private void work() {
        try {
            while (true) {
                TestResult r = results.poll(1, TimeUnit.SECONDS);
                if (r != null) {
                    sink.add(r);
                } else {
                    // terminated, time to go
                    if (terminated) return;
                }
            }
        } catch (InterruptedException e) {
            // interrupted, time to go
        }
    }

    @Override
    public void add(TestResult result) {
        try {
            results.put(result);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
