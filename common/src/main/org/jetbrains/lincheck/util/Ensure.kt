/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import kotlin.contracts.*

@OptIn(ExperimentalContracts::class)
fun Boolean.ensureTrue(): Boolean {
    contract {
        returns() implies (this@ensureTrue)
    }
    check(this == true)
    return this
}

@OptIn(ExperimentalContracts::class)
fun Boolean.ensureFalse(): Boolean {
    contract {
        returns() implies (!this@ensureFalse)
    }
    check(this == false)
    return this
}

inline fun<T> T.ensure(predicate: (T) -> Boolean): T {
    check(predicate(this))
    return this
}

inline fun<T> T.ensure(predicate: (T) -> Boolean, lazyMessage: (T?) -> Any): T {
    check(predicate(this)) { lazyMessage(this) }
    return this
}

@OptIn(ExperimentalContracts::class)
fun<T> T?.ensureNull(): T? {
    contract {
        returns() implies (this@ensureNull == null)
    }
    check(this == null)
    return this
}

@OptIn(ExperimentalContracts::class)
inline fun<T> T?.ensureNull(lazyMessage: (T?) -> Any): T? {
    contract {
        returns() implies (this@ensureNull == null)
    }
    check(this == null) { lazyMessage(this) }
    return this
}

@OptIn(ExperimentalContracts::class)
fun<T> T?.ensureNotNull(): T {
    contract {
        returns() implies (this@ensureNotNull != null)
    }
    check(this != null)
    return this
}

@OptIn(ExperimentalContracts::class)
inline fun<T> T?.ensureNotNull(lazyMessage: (T?) -> Any): T {
    contract {
        returns() implies (this@ensureNotNull != null)
    }
    check(this != null) { lazyMessage(this) }
    return this
}

// alias for `requireNoNulls`, used only for the naming scheme consistency
fun <T : Any> List<T?>.ensureNoNulls(): List<T> =
    requireNoNulls()
