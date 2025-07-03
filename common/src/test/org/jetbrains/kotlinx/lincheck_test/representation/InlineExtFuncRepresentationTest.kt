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

class InlineExtFuncRepresentationTest: BaseTraceRepresentationTest("inline_ext_fun") {
    var escape: Any? = null
    val i = 1

    @Suppress("NOTHING_TO_INLINE")
    private inline fun Int.setEscape(v: String) {
        escape = "$this$v"
    }

    override fun operation() {
        escape = "START"
        i.setEscape("SOME")
        escape = "END"
    }
}

