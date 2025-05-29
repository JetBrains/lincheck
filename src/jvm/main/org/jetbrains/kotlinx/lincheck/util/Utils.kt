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

fun <K, V> MutableMap<K, V>.update(key: K, default: V, transform: (V) -> V): V {
    // TODO: could it be done with a single lookup in a map?
    val value = transform(get(key) ?: default)
    put(key, value)
    return value
}

fun <K, V> MutableMap<K, V>.updateInplace(key: K, default: V, apply: V.() -> Unit) {
    computeIfAbsent(key) { default }.also(apply)
}