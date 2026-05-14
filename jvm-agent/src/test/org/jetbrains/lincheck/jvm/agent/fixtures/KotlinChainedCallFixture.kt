/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.fixtures

/**
 * Kotlin counterpart of [JavaChainedCallFixture]. The `?:` chain spread across two source
 * lines makes `kotlinc` emit four `LINENUMBER 22` directives for [findOrThrow]; the dedup
 * logic must collapse them to a single hook for a breakpoint installed at line 22.
 */
class KotlinChainedCallFixture {

    fun findOrThrow(id: Int?): Any =
        if (id == null) Any()
        else (id.takeIf { it > 0 }
            ?: throw IllegalArgumentException("not found: $id"))
}
