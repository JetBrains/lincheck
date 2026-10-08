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

/** Not public: a wrapper, defined in its own loader, cannot name this class and reaches its members reflectively. */
class PackagePrivateOwner {
    public final int id;
    private final String name;
    public final PackagePrivateStatus status;

    PackagePrivateOwner(int id, String name, PackagePrivateStatus status) {
        this.id = id;
        this.name = name;
        this.status = status;
    }

    public String name() {
        return name;
    }
}
