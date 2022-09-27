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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

class VectorClock<P, T>(
    val partialOrder: PartialOrder<T>,
    private val clock: MutableMap<P, T> = mutableMapOf()
) {

    operator fun get(part: P): T? = clock[part]

    fun observes(part: P, timestamp: T): Boolean =
        clock[part]?.let { partialOrder.lessOrEqual(timestamp, it) } ?: false

    operator fun set(part: P, timestamp: T) {
        clock[part] = timestamp
    }

    fun update(part: P, timestamp: T) {
        clock.update(part, default = timestamp) { oldTimestamp ->
            require(partialOrder.lessOrEqual(oldTimestamp, timestamp)) {
                "Attempt to update vector clock to older timestamp: old=$oldTimestamp, new=$timestamp."
            }
            timestamp
        }
    }

    operator fun plus(other: VectorClock<P, T>) =
        copy().apply { merge(other) }

    infix fun merge(other: VectorClock<P, T>) {
        require(partialOrder == other.partialOrder) {
            "Attempt to merge vector clocks ordered by differed partial orders."
        }
        clock.mergeReduce(other.clock, partialOrder::max)
    }

    fun copy(): VectorClock<P, T> =
        VectorClock<P, T>(partialOrder).also { it.clock += clock }

    fun asMap(): Map<P, T> = clock

    fun toMutableMap(): MutableMap<P, T> = clock.toMutableMap()

}