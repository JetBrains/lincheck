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

private class A(var b: B)
private class B(var a: A? = null)

private var globalA = A(B())

class StaticObjectCycleTest : SnapshotAbstractTest() {
    companion object {
        init {
            globalA.b.a = globalA
        }
    }

    private var initA = globalA
    private var initB = globalA.b

    @Operation
    fun modify() {
        globalA = A(B())
    }

    @After
    fun checkStaticStateRestored() {
        check(globalA == initA)
        check(globalA.b == initB)
        check(globalA.b.a == globalA)
    }
}