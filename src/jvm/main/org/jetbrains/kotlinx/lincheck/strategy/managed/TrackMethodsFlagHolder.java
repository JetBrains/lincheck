/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed;

public class TrackMethodsFlagHolder {

    /**
     * If set to true, then all methods enter and exit, as well as their arguments and receivers
     * are tracked by {@link ManagedStrategy}.
     */
    public static boolean trackingEnabled = false;
}
