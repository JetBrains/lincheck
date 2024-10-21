/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.junit.After
import org.junit.Before


private enum class Values {
    A, B, C;
}
private class EnumHolder(var x: Values, var y: Values)

private var global = EnumHolder(Values.A, Values.B)

class StaticEnumTest : SnapshotAbstractTest() {
    private var initA: EnumHolder = global
    private var initX: Values = global.x
    private var initY: Values = global.y

    @Operation
    fun modifyFields() {
        // modified fields of the initial instance
        global.x = Values.B
        global.y = Values.C

        // assign different instance to the variable
        global = EnumHolder(Values.C, Values.C)
    }

    @Before
    fun saveInitStaticState() {
    }

    @After
    fun checkStaticStateRestored() {
        check(global == initA)
        check(global.x == initX)
        check(global.y == initY)
    }
}