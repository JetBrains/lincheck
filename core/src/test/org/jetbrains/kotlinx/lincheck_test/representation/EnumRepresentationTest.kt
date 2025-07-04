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

class EnumRepresentationTest : BaseTraceRepresentationTest("enum_representation") {
    private var x: MyEnum? = null
    private var y: MyEnum? = null

    override fun operation() {
        x = MyEnum.VALUE_1
        y = x
        x = MyEnum.VALUE_2
        y = x
    }

}

private enum class MyEnum {
    VALUE_1, VALUE_2
}