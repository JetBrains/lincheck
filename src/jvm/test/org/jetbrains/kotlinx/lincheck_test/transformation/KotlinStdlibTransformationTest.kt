/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest

/**
 * This test checks that some methods in kotlin stdlib related to
 * `java.util` are correctly transformed.
 */
class KotlinStdlibTransformationTest : AbstractLincheckTest() {
    var hashCode = 0

    @Operation
    fun operation() {
        val intArray = IntArray(2)
        val objectArray = Array(2) { "" }
        val intProgression = (1..2)
        val objectList: List<String> = listOf("a", "b")
        intArray.iterator()
        intArray.forEach {
            // do something
            hashCode = it.hashCode()
        }
        objectArray.iterator()
        objectArray.forEach {
            // do something
            hashCode = it.hashCode()
        }
        intProgression.iterator()
        intProgression.forEach {
            // do something
            hashCode = it.hashCode()
        }
        for (string in objectList) {
            // do something
            hashCode = string.hashCode()
        }
        intArray.toList().toTypedArray()
        objectArray.toList().toTypedArray()
        intArray.toHashSet().sum()
        objectArray.toSortedSet()
        intProgression.toSet()
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
    }
}
