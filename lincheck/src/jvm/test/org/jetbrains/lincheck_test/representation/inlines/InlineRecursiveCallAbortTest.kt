/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.representation.inlines

import org.jetbrains.lincheck_test.representation.BaseTraceRepresentationTest
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions

/**
 * Aborting recursive method entry must preserve the trace while regular and inline frames unwind.
 */
class InlineRecursiveCallAbortTest : BaseTraceRepresentationTest("inlines/recursive_call_abort") {
    override fun operation() {
        recursiveCall()
    }

    private fun recursiveCall() {
        inlineCall { recursiveCall() }
    }

    private inline fun inlineCall(block: () -> Unit) {
        block()
    }

    override fun ModelCheckingOptions.customize() {
        recursionBound(3)
    }
}
