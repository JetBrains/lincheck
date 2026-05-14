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
 * Kotlin counterpart of [JavaBranchedSameLineFixture]. A range-for loop with an inline
 * `continue` for even values, so the increment basic block is reached both by body
 * fall-through AND by the `continue`'s forward jump — i.e. has multiple predecessors.
 * `kotlinc` desugars `for (i in 0 until bound)` to a counting structure whose header line
 * carries the initialiser and the increment, so multiple `LINENUMBER` directives for the
 * header line land in disjoint basic blocks, and the basic-block same-line dedup
 * preserves both — the dedup-doesn't-over-collapse invariant.
 */
class KotlinBranchedSameLineFixture {

    fun branchedSameLine(bound: Int): Int {
        var sum = 0
        for (i in 0 until bound) {
            if (i % 2 == 0) continue
            sum += i
        }
        return sum
    }
}
