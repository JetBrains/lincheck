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

/** Single-line `if/else`. `kotlinc` emits one `LINENUMBER` for the whole statement. */
class KotlinIfElseSingleLineFixture {

    fun ifElseSingleLine(x: Int): Int {
        val s: Int
        if (x > 0) { s = 1 } else { s = 2 }
        return s
    }
}
