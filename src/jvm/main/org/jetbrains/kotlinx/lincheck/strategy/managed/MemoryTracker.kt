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
 * This is just an example of memory tracking.
 *
 * TODO: Remove this class.
 */
class MemoryTracker {
    private val values = HashMap<Int, Any?>()

    fun writeValue(memoryLocationId: Int, value: Any?) {
        values[memoryLocationId] = value
    }

    /**
     * Either returns the last written value or default value if there were no writes.
     */
    fun readValue(memoryLocationId: Int, typeDescriptor: String): Any? =
        if (values.containsKey(memoryLocationId)) values[memoryLocationId] else defaultValueByDescriptor(typeDescriptor)
}

private fun defaultValueByDescriptor(descriptor: String): Any? = when (descriptor) {
    "I" -> 0
    "Z" -> false
    "B" -> 0.toByte()
    "C" -> 0.toChar()
    "S" -> 0.toShort()
    "J" -> 0.toLong()
    "D" -> 0.toDouble()
    "F" -> 0.toFloat()
    else -> null
}