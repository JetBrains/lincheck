/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.tracedata

import java.util.*

class MethodSignature(val name: String, val methodType: Types.MethodType) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MethodSignature) return false

        return (name == other.name &&
                methodType.equals(other.methodType))
    }

    override fun hashCode(): Int {
        return Objects.hash(name, methodType)
    }

    override fun toString(): String {
        return name + methodType
    }
}