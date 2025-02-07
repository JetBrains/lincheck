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
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import org.jetbrains.kotlinx.lincheck_test.util.jdkVersion
import org.junit.Assume.assumeFalse
import org.junit.Before

/**
 * This test checks that some methods in kotlin stdlib related to
 * `java.util` are correctly transformed.
 */
class KotlinStdlibTransformationTest : AbstractLincheckTest() {
    var hashCode = 0

    // TODO: this test causes TL on the CI with the implementation of https://github.com/JetBrains/lincheck/pull/469
    //       on java 20 & 21, thus, for these versions this test was disabled. However, this should be fixed later
    //       see issue https://github.com/JetBrains/lincheck/issues/508
    @Before
    fun setUp() = assumeFalse(jdkVersion == 20 || jdkVersion == 21)

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
