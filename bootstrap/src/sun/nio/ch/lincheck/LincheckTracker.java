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

public class LincheckTracker {
    private static EventTracker eventTracker;

    public static EventTracker getEventTracker() {
        return eventTracker;
    }

    public static void setEventTracker(EventTracker tracker) {
        eventTracker = tracker;
    }
}
