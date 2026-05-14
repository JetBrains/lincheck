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

/** Single-line `try/catch/finally`. `kotlinc` emits one `LINENUMBER` for the whole statement. */
class KotlinTryCatchFinallySingleLineFixture {

    fun tryCatchFinallySingleLine(x: Int): Int {
        var s = 0
        try { s = 10 / x } catch (e: ArithmeticException) { s = -1 } finally { s = touch(s) }
        return s
    }

    private fun touch(s: Int): Int = s
}
