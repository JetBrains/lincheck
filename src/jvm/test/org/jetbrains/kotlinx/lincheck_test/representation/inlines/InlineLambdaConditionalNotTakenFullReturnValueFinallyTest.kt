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
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.lincheck.util.isJdk8
import org.junit.Assume.assumeFalse
import org.junit.Before

class InlineLambdaConditionalNotTakenFullReturnValueFinallyTest: BaseTraceRepresentationTest("inlines/lambda_not_taken_full_return_value_finally") {
    var escape: Any? = null
    val i = 1

    @Before
    fun setUp() {
        // cannot run this test, as it has different output on JDK-8,
        // but due to https://github.com/JetBrains/lincheck/issues/500
        // we cannot set trace-debugger & JDK-8 specific expected output file
        assumeFalse(isInTraceDebuggerMode || isJdk8)
    }

    override fun operation() {
        val x = returnInt()
    }

    fun returnInt(): Int {
        escape = "START"
        run {
            escape = "IN LAMBDA"
            if (isFalse) return -1
            escape = "AFTER LAMBDA"
            return -5
        }
        escape = "END"
        return 0
    }

    inline fun run(block: () -> Int) {
        escape = "BEFORE BLOCK"
        try {
            block()
        } finally {
            escape = "AFTER BLOCK"
        }
    }

    val isFalse = System.getProperty("--NOT-EXISTENT--").toBoolean()
}

