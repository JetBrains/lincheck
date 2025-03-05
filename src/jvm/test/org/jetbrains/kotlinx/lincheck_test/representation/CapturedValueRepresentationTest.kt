/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation

/**
 * This test checks that values captured in an incorrect interleaving have proper representation.
 * `toString` method is used only for primitive types and their wrappers.
 * For other classes we use simplified representation to avoid problems with concurrent modification or
 * not completely initialized objects (e.g, with `ConcurrentModificationException`)
 */
class CapturedValueRepresentationTest : BaseTraceRepresentationTest("captured_value") {
    private var outerClass1 = OuterDataClass(0)
    private var outerClass2 = OuterDataClass(0)
    private var innerClass = InnerClass()
    private var otherInnerClass = InnerClass()
    private var primitiveArray = IntArray(1)
    private var objectArray = Array(1) { "" }

    override fun operation() {
        outerClass1
        outerClass2
        innerClass
        innerClass
        otherInnerClass
        primitiveArray
        objectArray
    }

    private class InnerClass
}

private data class OuterDataClass(val a: Int)
