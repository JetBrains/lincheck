/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

/** A relation whose computed result remains cached until [invalidate] is called. */
interface CachedRelation<T> : Relation<T> {
    /** Computes the relation, or does nothing if its cached result is still valid. */
    fun compute()

    /** Discards the cached result so the next [compute] uses the current execution. */
    fun invalidate()
}
