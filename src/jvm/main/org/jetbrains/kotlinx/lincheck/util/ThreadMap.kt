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

typealias ThreadMap<T> = MutableMap<ThreadId, T>
typealias MutableThreadMap<T> = MutableMap<ThreadId, T>

fun <T> ThreadMap(nThreads: Int, initializer: (Int) -> T): ThreadMap<T> =
    MutableThreadMap(nThreads, initializer)

fun <T> MutableThreadMap(nThreads: Int, initializer: (Int) -> T): MutableThreadMap<T> =
    mutableMapOf(
        *(0 until nThreads).map { it to initializer(it) }.toTypedArray()
    )

fun <K, V> MutableMap<K, V>.update(key: K, default: V, transform: (V) -> V) {
    // TODO: could it be done with a single lookup in a map?
    put(key, get(key)?.let(transform) ?: default)
}

fun <K, V> MutableMap<K, V>.updateInplace(key: K, default: V, apply: V.() -> Unit) {
    computeIfAbsent(key) { default }.also(apply)
}