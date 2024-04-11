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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.*

class EnumRepresentationTest {
    private var x: MyEnum = MyEnum.VALUE_1
    private var counter = 0

    @Operation
    fun operation(): Int {
        x = MyEnum.VALUE_2
        return counter++
    }

    @Test
    fun test() = ModelCheckingOptions()
        .checkImpl(this::class.java)
        .checkLincheckOutput("enum_representation.txt")
}

private enum class MyEnum {
    VALUE_1, VALUE_2
}