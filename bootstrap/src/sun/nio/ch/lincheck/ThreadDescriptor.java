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

import java.lang.ref.WeakReference;

public class ThreadDescriptor {

    /**
     * The thread instance associated with this descriptor.
     */
    private final WeakReference<Thread> thread;

    /**
     * The {@code EventTracker} for tracking events in the model checking mode.
     */
    private WeakReference<EventTracker> eventTracker = new WeakReference<>(null);

    /**
     * Holds additional event-tracker-specific data associated with the thread.
     */
    private WeakReference<Object> eventTrackerData = null;

    /**
     * This flag indicates whether the Lincheck is currently running analyzed test code.
     */
    private boolean inTestingCode = false;

    /**
     * This flag is used to disable tracking of all events.
     *
     * <p>
     * If Lincheck enters a code block for which analysis should be disabled, this flag is set to `true`.
     * Notably, such code blocks can be nested, but only the outermost one changes the flag.
     */
    private boolean inIgnoredSection = false;

    public ThreadDescriptor(Thread thread) {
        if (thread == null) {
            throw new IllegalArgumentException("Thread must not be null");
        }
        this.thread = new WeakReference<>(thread);
    }

    public Thread getThread() {
        return thread.get();
    }

    public EventTracker getEventTracker() {
        return eventTracker.get();
    }

    public void setEventTracker(EventTracker eventTracker) {
        this.eventTracker = new WeakReference<>(eventTracker);
    }

    public Object getEventTrackerData() {
        return eventTrackerData.get();
    }

    public void setEventTrackerData(Object eventTrackerData) {
        this.eventTrackerData = new WeakReference<>(eventTrackerData);
    }

    public boolean inIgnoredSection() {
        return !inTestingCode || inIgnoredSection;
    }

    public boolean enterIgnoredSection() {
        if (inIgnoredSection) return false;
        inIgnoredSection = true;
        return true;
    }

    public void leaveIgnoredSection() {
        inIgnoredSection = false;
    }

    public boolean inTestingCode() {
        return inTestingCode;
    }

    public void enterTestingCode() {
        inTestingCode = true;
    }

    public void leaveTestingCode() {
        inTestingCode = false;
    }
}
