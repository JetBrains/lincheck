/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck

/**
 * Type of result used if the actor invocation returns any value.
 */
actual class ValueResult(actual val value: Any?, override val wasSuspended: Boolean = false) : Result(), Finalizable {
    actual override fun equals(other: Any?): Boolean = if (other !is ValueResult) false else other.wasSuspended == wasSuspended && other.value == value

    actual override fun hashCode(): Int = if (wasSuspended) 0 else 1 // We can't use value here

    override fun toString() = wasSuspendedPrefix + "$value"

    override fun finalize() {
        if(value is Finalizable) {
            value.finalize()
        }
    }
}