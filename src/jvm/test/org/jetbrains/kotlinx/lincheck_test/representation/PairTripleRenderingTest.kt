/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

class PairTripleRenderingTest : BaseTraceRepresentationTest("pair_triple_rendering") {
    var pair = 1 to 2

    override fun operation() {
        foo(Triple("a", 3, 4.0))
    }

    fun foo(triple: Triple<String, Int, Double>) = pair to triple
}