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

/**
 * Base class for code location -- an object which describes some position
 * in source code where a certain event has happened.
 */
sealed class CodeLocation(
    val stackTraceElement: StackTraceElement,
    /**
     * List of active local variables at the position of the code location.
     */
    val activeLocals: List<ActiveLocal>? = null,
)

/**
 * Code location used in tracepoints which describe access to a field or an array element.
 */
class AccessCodeLocation(
    stackTraceElement: StackTraceElement,
    /**
     * Access path to the field or array element.
     * Might contain a parent object with the field name or
     * array variable name with the element index.
     */
    val accessPath: AccessPath?,
    activeLocals: List<ActiveLocal>? = null,
) : CodeLocation(stackTraceElement, activeLocals) {
    override fun hashCode(): Int {
        var result = stackTraceElement.hashCode()
        result = 31 * result + accessPath.hashCode()
        result = 31 * result + (activeLocals?.hashCode() ?: 0)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccessCodeLocation) return false
        return stackTraceElement == other.stackTraceElement &&
               accessPath        == other.accessPath        &&
               activeLocals      == other.activeLocals
    }
}

/**
 * Code location used in method calls tracepoints.
 */
class MethodCallCodeLocation(
    stackTraceElement: StackTraceElement,
    /**
     * Access path to the method being called. Expected to be the owner object name
     * on which the method is invoked, or null if the method is static.
     */
    val accessPath: AccessPath?,
    /**
     * Names of the method arguments.
     */
    val argumentNames: List<AccessPath?>?,
    activeLocals: List<ActiveLocal>? = null,
) : CodeLocation(stackTraceElement, activeLocals) {
    override fun hashCode(): Int {
        var result = stackTraceElement.hashCode()
        result = 31 * result + accessPath.hashCode()
        result = 31 * result + (argumentNames?.hashCode() ?: 0)
        result = 31 * result + (activeLocals?.hashCode() ?: 0)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MethodCallCodeLocation) return false
        return stackTraceElement == other.stackTraceElement &&
               accessPath        == other.accessPath        &&
               argumentNames     == other.argumentNames     &&
               activeLocals      == other.activeLocals
    }
}

/**
 * Code location used in any other type of tracepoints which do not
 * require any special information to be stored in their code location.
 */
class LineCodeLocation(
    stackTraceElement: StackTraceElement,
    activeLocals: List<ActiveLocal>? = null
) : CodeLocation(stackTraceElement, activeLocals) {
    override fun hashCode(): Int {
        var result = stackTraceElement.hashCode()
        result = 31 * result + (activeLocals?.hashCode() ?: 0)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LineCodeLocation) return false
        return stackTraceElement == other.stackTraceElement &&
               activeLocals      == other.activeLocals
    }
}

val CodeLocation.accessPath: AccessPath? get() = when (this) {
    is AccessCodeLocation -> accessPath
    is MethodCallCodeLocation -> accessPath
    else -> null
}

val CodeLocation.argumentNames: List<AccessPath?>? get() = when (this) {
    is MethodCallCodeLocation -> argumentNames
    else -> null
}