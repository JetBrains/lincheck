/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package fuzzing.ConcurrencyOptimalTreeMap;

public interface MaintenanceAlg {

    /**
     * If set to true then a lock-free version of the algorithm is used
     */
    static final boolean lockFree = true;

    public boolean stopMaintenance();
    public long getStructMods();
    public int numNodes();
}