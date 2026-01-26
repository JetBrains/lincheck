/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

typealias ThreadId = Int
typealias ThreadMap<T> = Map<ThreadId, T>
typealias MutableThreadMap<T> = MutableMap<ThreadId, T>

fun <T> threadMapOf(): ThreadMap<T> =
    mutableThreadMapOf()

fun <T> threadMapOf(vararg pairs: Pair<ThreadId, T>): ThreadMap<T> =
    mutableThreadMapOf(*pairs)

fun <T> mutableThreadMapOf(): MutableThreadMap<T> =
    mutableMapOf()

fun <T> mutableThreadMapOf(vararg pairs: Pair<ThreadId, T>): ThreadMap<T> =
    mutableMapOf(*pairs)


// Methods moved from ArrayIntMap
fun <T> MutableThreadMap<T>.update(key: Int, default: T, transform: (T) -> T) {
    // TODO: could it be done with a single lookup in a map?
    put(key, get(key)?.let(transform) ?: default)
}

fun <T> MutableThreadMap<T>.mergeReduce(other: ThreadMap<T>, reduce: (T, T) -> T) {
    other.forEach { (key, value) ->
        update(key, default = value) { reduce(it, value) }
    }
}
