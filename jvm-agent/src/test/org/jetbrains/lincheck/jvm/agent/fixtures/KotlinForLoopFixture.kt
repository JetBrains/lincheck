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
 * Kotlin counterpart of [JavaForLoopFixture]. Kotlin's `for (i in 0 until bound)` desugars
 * to a counting loop whose header line carries both the initialiser and the increment;
 * `kotlinc` emits multiple `LINENUMBER` directives for the header line in disjoint basic
 * blocks separated by the loop body BB and by the back-edge target. The basic-block
 * same-line dedup preserves them, matching JDI's "a line may have more than one
 * executable location" semantics.
 */
class KotlinForLoopFixture {

    fun forLoopShape(bound: Int): Int {
        var sum = 0
        for (i in 0 until bound) {
            sum += i
        }
        return sum
    }
}
