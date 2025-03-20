/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck;


import java.util.Objects;

public class MethodSignature {
    private final String name;
    private final Types.MethodType methodType;

    public MethodSignature(String name, Types.MethodType methodType) {
        this.name = name;
        this.methodType = methodType;
    }

    public String getName() {
        return name;
    }

    public Types.MethodType getMethodType() {
        return methodType;
    }

    @Override
    public boolean equals(java.lang.Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MethodSignature)) return false;

        MethodSignature other = (MethodSignature) obj;
        return (
            name.equals(other.name) &&
            methodType.equals(other.methodType)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, methodType);
    }
}