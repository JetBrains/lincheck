/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import java.util.concurrent.ConcurrentHashMap

/**
 * Assigns unique identifiers to every shared memory location.
 *
 * TODO: support accessing memory locations via Unsafe/AFU/VarHandle.
 */
class MemoryLocationLabeler {
    // TODO: make a typealias `MemoryLocationID = Int` ?
    private val memoryLocations = ConcurrentHashMap<MemoryLocation, Int>()

    fun labelStaticField(className: String, fieldName: String): Int =
        label(null, Pair(className, fieldName))

    fun labelObjectField(obj: Any, fieldName: String): Int =
        label(obj, fieldName)

    fun labelArrayElement(array: Any, position: Int): Int =
        label(array, position)

    private fun label(owner: Any?, marker: Any): Int = memoryLocations.computeIfAbsent(MemoryLocation(owner, marker)) { memoryLocations.size }

    /**
     * Custom class since owners should be compared by identity, while markers should be compared via `equals`.
     */
    private class MemoryLocation(val owner: Any?, val marker: Any) {
        override fun equals(other: Any?): Boolean {
            return other is MemoryLocation && (owner === other.owner && marker == other.marker)
        }

        override fun hashCode(): Int {
            // do not call owner.hashCode, because it can lead to infinite recursion
            return marker.hashCode()
        }
    }
}