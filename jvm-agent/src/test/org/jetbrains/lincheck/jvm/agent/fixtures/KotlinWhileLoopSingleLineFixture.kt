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

/** Single-line `while` loop. `kotlinc` emits one `LINENUMBER` for the whole statement. */
class KotlinWhileLoopSingleLineFixture {

    fun whileLoopSingleLine(bound: Int): Int {
        var i = 0
        while (i < bound) { i++ }
        return i
    }
}
