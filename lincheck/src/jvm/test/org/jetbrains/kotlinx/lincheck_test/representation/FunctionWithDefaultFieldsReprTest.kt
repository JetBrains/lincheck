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


class FunctionWithDefaultFieldsReprTest: BaseTraceRepresentationTest("function_with_default_fields_repr_test") {
    
    @Volatile
    private var a = 0

    override fun operation() {
        callMe(3)
        callMe()
    }

    private fun callMe(int: Int = 1, str: String = "Hey") {
        a += str.length
        a += int
        callOther(5)
    }
    
    private fun callOther(int: Int = 1, str: String = "Hey") {
        a += str.length
        a += int
    }
}

