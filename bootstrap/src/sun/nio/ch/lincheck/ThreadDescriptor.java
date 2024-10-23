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

public class ThreadDescriptor {
    private EventTracker eventTracker = null;
    private Object eventTrackerData = null;

    private boolean inTestingCode = false;
    private boolean inIgnoredSection = false;

    public EventTracker getEventTracker() {
        return eventTracker;
    }

    public void setEventTracker(EventTracker eventTracker) {
        this.eventTracker = eventTracker;
    }

    public Object getEventTrackerData() {
        return eventTrackerData;
    }

    public void setEventTrackerData(Object eventTrackerData) {
        this.eventTrackerData = eventTrackerData;
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
