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
 * Kotlin counterpart of [JavaTryCatchFinallyMultiLineFixture]. `kotlinc` inlines the
 * `finally` body into the try-success, catch-success, and exception-rethrow paths,
 * producing separate `LINENUMBER` directives for the finally body line in disjoint basic
 * blocks. The basic-block same-line dedup preserves all of them — the catch-handler
 * and rethrow-handler entries are exception-target labels, which reset the per-block
 * "lines already emitted" set.
 */
class KotlinTryCatchFinallyMultiLineFixture {

    fun tryCatchFinallyMultiLine(x: Int): Int {
        var s = 0
        try {
            s = 10 / x
        } catch (e: ArithmeticException) {
            s = -1
        } finally {
            s = touch(s)
        }
        return s
    }

    private fun touch(s: Int): Int = s
}
