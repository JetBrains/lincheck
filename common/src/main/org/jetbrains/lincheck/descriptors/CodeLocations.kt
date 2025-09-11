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

import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import java.util.Objects

class CodeLocation(
    val stackTraceElement: StackTraceElement,
    val accessPath: AccessPath? = null,

    // TODO: this only makes sense for method call code locations,
    //   consider introducing proper type hierarchy for code locations
    val argumentNames: List<AccessPath?>? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CodeLocation) return false

        // TODO: argumentNames are not considered here,
        //   because of some weird bug that occurs if we consider them;
        //   likely, this is related to the fact that we currently do not (de)serialize them
        return (stackTraceElement == other.stackTraceElement) && (accessPath == other.accessPath)
    }

    override fun hashCode(): Int {
        return Objects.hash(stackTraceElement, accessPath)
    }
}

/**
 * [CodeLocations] object is used to maintain the mapping between unique IDs and code locations.
 * When Lincheck detects an error in the model checking mode, it provides a detailed interleaving trace.
 * This trace includes a list of all shared memory events that occurred during the execution of the program,
 * along with their corresponding code locations. To minimize overhead, Lincheck assigns unique IDs to all
 * code locations it analyses, and stores more detailed information necessary for trace generation in this object.
 */
object CodeLocations {
    /**
     * Registers a new code location and returns its unique ID.
     *
     * @param stackTraceElement Stack trace element representing the new code location.
     * @return Unique ID of the new code location.
     */
    @JvmStatic
    @Synchronized
    fun newCodeLocation(
        stackTraceElement: StackTraceElement,
        accessPath: AccessPath? = null,
        argumentNames: List<AccessPath?>? = null,
    ): Int =
        TRACE_CONTEXT.newCodeLocation(stackTraceElement, accessPath, argumentNames)

    /**
     * Returns the [StackTraceElement] associated with the specified code location ID.
     *
     * @param codeLocationId ID of the code location.
     * @return [StackTraceElement] corresponding to the given ID.
     */
    @JvmStatic
    @Synchronized
    fun stackTrace(codeLocationId: Int): StackTraceElement = TRACE_CONTEXT.stackTrace(codeLocationId)

    @JvmStatic
    @Synchronized
    fun accessPath(codeLocationId: Int): AccessPath? = TRACE_CONTEXT.accessPath(codeLocationId)
}