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

internal infix fun <T> ((T) -> Boolean).and(other: (T) -> Boolean): (T) -> Boolean = { this(it) && other(it) }
internal infix fun <T> ((T) -> Boolean).or(other: (T) -> Boolean): (T) -> Boolean = { this(it) || other(it) }
internal fun <T> not(predicate: (T) -> Boolean): (T) -> Boolean = { !predicate(it) }
