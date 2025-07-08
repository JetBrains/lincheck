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

internal fun Boolean.ensureTrue(): Boolean {
    // TODO: add contracts?
    check(this == true)
    return this
}

internal fun Boolean.ensureFalse(): Boolean {
    // TODO: add contracts?
    check(this == false)
    return this
}

internal inline fun<T> T.ensure(predicate: (T) -> Boolean): T {
    // TODO: add contracts?
    check(predicate(this))
    return this
}

internal inline fun<T> T.ensure(predicate: (T) -> Boolean, lazyMessage: (T?) -> Any): T {
    // TODO: add contracts?
    check(predicate(this)) { lazyMessage(this) }
    return this
}

internal fun<T> T?.ensureNull(): T? {
    // TODO: add contracts?
    check(this == null)
    return this
}

internal fun<T> T?.ensureNull(lazyMessage: (T?) -> Any): T? {
    // TODO: add contracts?
    check(this == null) { lazyMessage(this) }
    return this
}