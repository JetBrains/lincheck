/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

infix fun Boolean.implies(other: Boolean): Boolean =
    !this || other

inline infix fun Boolean.implies(other: () -> Boolean): Boolean =
    !this || other()

infix fun Boolean.equivalent(other: Boolean): Boolean =
    (this && other) || (!this && !other)

internal infix fun <T> ((T) -> Boolean).and(other: (T) -> Boolean): (T) -> Boolean = { this(it) && other(it) }
internal infix fun <T> ((T) -> Boolean).or(other: (T) -> Boolean): (T) -> Boolean = { this(it) || other(it) }
internal fun <T> not(predicate: (T) -> Boolean): (T) -> Boolean = { !predicate(it) }

fun Boolean.toInt(): Int = this.compareTo(false)
fun Int.toBoolean() = (this != 0)

fun Byte.toBoolean(): Boolean = when (this) {
    0.toByte() -> false
    1.toByte() -> true
    else -> throw IllegalArgumentException("Byte $this is not a Boolean")
}

inline fun<T> T.runIf(boolean: Boolean, block: T.() -> T): T =
    if (boolean) block() else this

inline fun<reified T> Any?.satisfies(predicate: T.() -> Boolean): Boolean =
    this is T && predicate(this)

inline fun<reified T> Any?.refine(predicate: T.() -> Boolean): T? =
    if (this is T && predicate(this)) this else null

@Suppress("UNCHECKED_CAST")
inline fun<reified T> List<Any?>.refine(): List<T>? {
    return if (all { it is T }) (this as List<T>) else null
}

