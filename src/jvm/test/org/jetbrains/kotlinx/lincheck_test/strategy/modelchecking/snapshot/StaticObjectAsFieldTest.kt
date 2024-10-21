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


private object Static1 {
    var f1: Static2? = Static2
    var f2: Int = 0
}

private object Static2 {
    var f1: Int = 1
    var f2: String = "abc"
}

class StaticObjectAsFieldTest : SnapshotAbstractTest() {
    private val initS1f1 = Static1.f1
    private val initS1f2 = Static1.f2

    private val initS2f1 = Static2.f1
    private val initS2f2 = Static2.f2

    @Operation
    fun modify() {
        Static2.f1 = 10
        Static2.f2 = "cba"

        Static1.f1 = null
        Static1.f2 = 10
    }

    @After
    fun checkStaticStateRestored() {
        check(Static1.f1 == initS1f1 && Static1.f2 == initS1f2)
        check(Static2.f1 == initS2f1 && Static2.f2 == initS2f2)
    }
}