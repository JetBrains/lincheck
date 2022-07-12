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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstruct

enum class Ordering {
    Less, Equal, Greater, Incomparable;

    companion object {
        fun ofInt(i: Int): Ordering {
            return when {
                i < 0 -> Less
                i > 0 -> Greater
                else  -> Equal
            }
        }
    }
}

class IncomparableArgumentsException(message: String): Exception(message)

/**
 * Partial order allows to compare elements of given type [T],
 * some elements of [T] might be incomparable.
 */
class PartialOrder<T>(val lessOrEqual: Relation<T>, val lessThan: Relation<T>) {

    companion object {
        fun<T> ofLessOrEqual(lessOrEqual: Relation<T>): PartialOrder<T> {
            val lessThan = Relation<T> { x, y -> (x != y) && lessOrEqual(x, y) }
            return PartialOrder(lessOrEqual, lessThan)
        }

        fun<T> ofLessThan(lessThan: Relation<T>): PartialOrder<T> {
            val lessOrEqual = Relation<T> { x, y -> (x == y) || lessThan(x, y) }
            return PartialOrder(lessOrEqual, lessThan)
        }
    }

    val nullOrLessOrEqual = Relation<T?> { x, y ->
        if (x == null) y == null
        else lessOrEqual(x, y ?: return@Relation false)
    }

    fun lessOrEqualWithDefault(x: T?, y: T?, default: T) =
        lessOrEqual(x ?: default, y ?: default)

    fun compare(x: T, y: T): Ordering {
        return when {
            (x == y) -> Ordering.Equal
            lessThan(x, y) -> Ordering.Less
            lessThan(y, x) -> Ordering.Greater
            else -> Ordering.Incomparable
        }
    }

    fun maxOrNull(x: T, y: T): T? =
        when {
            lessOrEqual(x, y) -> y
            lessOrEqual(y, x) -> x
            else -> null
        }

    fun max(x: T, y: T): T =
        maxOrNull(x, y) ?:
            throw IncomparableArgumentsException("$x and $y are incomparable")

}