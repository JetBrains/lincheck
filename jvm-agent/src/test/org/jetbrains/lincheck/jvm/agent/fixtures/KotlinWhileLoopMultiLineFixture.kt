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
 * Kotlin counterpart of [JavaWhileLoopMultiLineFixture]. `kotlinc` emits one `LINENUMBER`
 * for the `while`-header line (no separate init/increment to duplicate), so a breakpoint
 * at the header line resolves to exactly one hook.
 */
class KotlinWhileLoopMultiLineFixture {

    fun whileLoopMultiLine(bound: Int): Int {
        var i = 0
        while (i < bound) {
            i++
        }
        return i
    }
}
