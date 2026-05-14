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
 * Kotlin counterpart of [JavaForLoopSingleLineFixture]. The entire `for (i in 0 until
 * bound) { s += i }` sits on one source line, so `kotlinc` emits a single `LINENUMBER`
 * for the loop body regardless of the desugared counting structure.
 */
class KotlinForLoopSingleLineFixture {

    fun forLoopSingleLine(bound: Int): Int {
        var s = 0
        for (i in 0 until bound) { s += i }
        return s
    }
}
