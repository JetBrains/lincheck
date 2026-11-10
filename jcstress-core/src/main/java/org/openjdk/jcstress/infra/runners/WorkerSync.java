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


import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.LockSupport;

/**
 * @author Aleksey Shipilev (aleksey.shipilev@oracle.com)
 */
@sun.misc.Contended
@jdk.internal.vm.annotation.Contended
public class WorkerSync {

    public final boolean stopping;
    public final SpinLoopStyle spinStyle;

    private volatile int notConsumed;
    private volatile int notUpdated;
    private volatile int checkpoint;

    static final AtomicIntegerFieldUpdater<WorkerSync> UPDATER_NOT_CONSUMED = AtomicIntegerFieldUpdater.newUpdater(WorkerSync.class, "notConsumed");
    static final AtomicIntegerFieldUpdater<WorkerSync> UPDATER_NOT_UPDATED = AtomicIntegerFieldUpdater.newUpdater(WorkerSync.class, "notUpdated");
    static final AtomicIntegerFieldUpdater<WorkerSync> UPDATER_CHECKPOINT = AtomicIntegerFieldUpdater.newUpdater(WorkerSync.class, "checkpoint");

    public WorkerSync(boolean stopping, int expectedWorkers, SpinLoopStyle spinStyle) {
        this.stopping = stopping;
        this.spinStyle = spinStyle;
        this.notConsumed = expectedWorkers;
        this.notUpdated = expectedWorkers;
    }

    public void awaitCheckpoint(int expected) {
        // Notify that we have rolled to the checkpoint
        UPDATER_CHECKPOINT.incrementAndGet(this);

        switch (spinStyle) {
            case HARD:
                while (checkpoint < expected);
                break;
            case THREAD_YIELD:
                while (checkpoint < expected) Thread.yield();
                break;
            case THREAD_SPIN_WAIT:
                while (checkpoint < expected) Thread.onSpinWait();
                break;
            case LOCKSUPPORT_PARK_NANOS:
                while (checkpoint < expected) LockSupport.parkNanos(1);
                break;
            default:
                throw new IllegalStateException("Unhandled style: " + spinStyle);
        }
    }

    public boolean tryStartUpdate()  {
        return (UPDATER_NOT_CONSUMED.decrementAndGet(this) == 0);
    }

    public void postUpdate() {
        UPDATER_NOT_UPDATED.decrementAndGet(this);

        switch (spinStyle) {
            case HARD:
                while (notUpdated > 0);
                break;
            case THREAD_YIELD:
                while (notUpdated > 0) Thread.yield();
                break;
            case THREAD_SPIN_WAIT:
                while (notUpdated > 0) Thread.onSpinWait();
                break;
            case LOCKSUPPORT_PARK_NANOS:
                while (notUpdated > 0) LockSupport.parkNanos(1);
                break;
            default:
                throw new IllegalStateException("Unhandled style: " + spinStyle);
        }
    }

}
