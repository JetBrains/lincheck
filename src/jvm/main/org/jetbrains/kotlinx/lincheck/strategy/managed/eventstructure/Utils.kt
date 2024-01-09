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


fun <K, V> MutableMap<K, V>.update(key: K, default: V, transform: (V) -> V) {
    // TODO: could it be done with a single lookup in a map?
    put(key, get(key)?.let(transform) ?: default)
}

fun <K, V> MutableMap<K, V>.mergeReduce(other: Map<K, V>, reduce: (V, V) -> V) {
    other.forEach { (key, value) ->
        update(key, default = value) { reduce(it, value) }
    }
}

inline fun<T> T.runIf(boolean: Boolean, block: T.() -> T): T =
    if (boolean) block() else this
