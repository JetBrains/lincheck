/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.descriptors

class CodeLocation(
    val stackTraceElement: StackTraceElement,
    val accessPath: AccessPath? = null,

    // TODO: this only makes sense for method call code locations,
    //   consider introducing proper type hierarchy for code locations
    val argumentNames: List<AccessPath?>? = null,
    val activeLocals: List<ActiveLocal>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CodeLocation) return false

        return (stackTraceElement == other.stackTraceElement) && (accessPath == other.accessPath) && (argumentNames == other.argumentNames)
    }

    override fun hashCode(): Int {
        var result = stackTraceElement.hashCode()
        result = 31 * result + (accessPath?.hashCode() ?: 0)
        result = 31 * result + (argumentNames?.hashCode() ?: 0)
        return result
    }
}