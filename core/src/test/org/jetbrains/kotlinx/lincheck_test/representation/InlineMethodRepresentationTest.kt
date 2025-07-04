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

class InlineMethodRepresentationTest: BaseTraceRepresentationTest("inline_method") {
    private val isc = InlineSetterClass()

    var escape: Any? = null

    private inline fun thisClassInlineFunc(value: String, setter: (String) -> Unit) {
        isc.otherClassInlineFunc(value, setter)
    }

    override fun operation() {
        escape = "START"
        thisClassInlineFunc("INLINE") {
            escape = it
        }
        escape = "END"
    }
}

private class InlineSetterClass {
    inline fun otherClassInlineFunc(value: String, setter: (String) -> Unit) {
        outsideInlineFunc(value, setter)
    }
}

internal inline fun outsideInlineFunc(value: String, setter: (String) -> Unit) {
    setter(value)
}