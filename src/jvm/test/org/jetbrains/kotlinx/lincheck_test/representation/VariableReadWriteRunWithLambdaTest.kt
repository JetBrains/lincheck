/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

class VariableReadWriteRunWithLambdaTest : BaseRunWithLambdaRepresentationTest("variable_read_write_run_with_lambda.txt") {
    private var x = 0

    @Suppress("UNUSED_VARIABLE")
    override fun operation() {
        x++
        val y = --x
    }
}