/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.expressions.fixtures;

/** Hands the package-private fixtures to tests in other packages. */
public final class PackagePrivateFixtures {
    private PackagePrivateFixtures() {
    }

    public static Class<?> ownerType() {
        return PackagePrivateOwner.class;
    }

    public static Class<?> statusType() {
        return PackagePrivateStatus.class;
    }

    public static Object owner(int id, String name, boolean active) {
        return new PackagePrivateOwner(id, name, active ? PackagePrivateStatus.ACTIVE : PackagePrivateStatus.INACTIVE);
    }

    public static Object activeStatus() {
        return PackagePrivateStatus.ACTIVE;
    }
}
