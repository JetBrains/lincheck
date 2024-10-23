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
 * When Lincheck runs a test, all threads should be instances of this TestThread class.
 * This class provides additional fields and controls for the Lincheck testing framework.
 * It also names the thread based on the test being run for easier debugging and tracking.
 */
public class TestThread extends Thread {
    public final int threadId;

    /**
     * The `EventTracker` for tracking shared memory events in the model checking mode.
     */
    public EventTracker eventTracker;

    /**
     * The currently suspended continuation, if present.
     * It's stored here to provide a handle for resumption during testing.
     *
     * It's necessary to store it in `Object`type, otherwise we would have to load `CancellableContinuation` class earlier.
     * But actually `suspendedContinuation` is always of `CancellableContinuation` type.
     */
    public Object suspendedContinuation;

    /**
     * This flag indicates whether the Lincheck is currently running user's code.
     *
     * - When it is `true`, Lincheck is running user's code and analyzes it.
     * - When it is `false`, the analysis is disabled.
     */
    public boolean inTestingCode = false;

    /**
     * This flag is used to disable tracking of all code events.
     * If Lincheck enters a code block for which analysis should be disabled,
     * this flag is set to `true`. Notably, such code blocks can be nested,
     * but only the most outer one changes the flag.
     */
    public boolean inIgnoredSection = false;

    /**
     * @param testName The name of the test currently being run.
     * @param threadId The index of the thread.
     * @param block    The Runnable object associated with the thread.
     */
    public TestThread(String testName, int threadId, Runnable block) {
        super(block, "Lincheck-" + testName + "-" + threadId);
        this.threadId = threadId;
        Injections.setThreadDescriptor(this, new ThreadDescriptor());
    }

    @Override
    public void run() {
        var descriptor = Injections.getThreadDescriptor(this);
        Injections.setCurrentThreadDescriptor(descriptor);
        super.run();
    }
}