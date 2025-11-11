/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation.inlines

import org.jetbrains.kotlinx.lincheck_test.representation.BaseTraceRepresentationTest

class InlineClassTest: BaseTraceRepresentationTest("inline_class_with_default") {
    internal inline fun inlineFunctionWithDefault(x: UInt, y: UInt = 10U, f: (UInt, UInt) -> UInt) = f(x, y)

    override fun operation() {
        println(inlineFunctionWithDefault(2U, 3U) { x, y -> x + y })
        println(inlineFunctionWithDefault(2U) { x, y -> x + y })
    }
}