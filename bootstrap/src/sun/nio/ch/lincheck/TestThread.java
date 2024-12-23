/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck;

/**
 * A thread subclass to represent test threads created by the Lincheck itself.
 * This class provides additional fields and controls for the Lincheck testing framework.
 * It also names the thread based on the test being run for easier debugging and tracking.
 */
public class TestThread extends Thread {

    /**
     * The unique identifier for the thread in the context of the Lincheck test.
     */
    public final int threadId;

    /**
     * The thread descriptor.
     */
    public ThreadDescriptor descriptor;

    /**
     * The currently suspended continuation, if present.
     * It's stored here to provide a handle for resumption during testing.
     *
     * <p>
     * It's necessary to store it in {@code Object} type,
     * otherwise we would have to load {@code CancellableContinuation} class earlier.
     * But actually {@code suspendedContinuation} is always of {@code CancellableContinuation} type.
     */
    public Object suspendedContinuation;

    /**
     * @param testName The name of the test currently being run.
     * @param threadId The index of the thread.
     * @param block    The Runnable object associated with the thread.
     */
    public TestThread(String testName, int threadId, Runnable block) {
        super(block, "Lincheck-" + testName + "-" + threadId);
        this.threadId = threadId;
        ThreadDescriptor.setThreadDescriptor(this, new ThreadDescriptor(this));
    }
}