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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger


@Suppress("UNUSED_PARAMETER", "SameParameterValue")
class ParametersCapturingTransformationTest {

    private val counter = AtomicInteger(1)

    @Operation
    fun operation() {
        primitiveValues(
            d = 1.2,
            f = 1.2.toFloat(),
            l = 2.toLong(),
            i = 1,
            b = true,
            c = Char.MAX_VALUE,
            s = Short.MAX_VALUE,
        )
        boxedValues(
            boxedInt = java.lang.Integer.valueOf(1),
            boxedBoolean = java.lang.Boolean.valueOf(true),
            boxedChar = java.lang.Character.valueOf('a'),
            boxedLong = java.lang.Long.valueOf(1),
            boxedByte = java.lang.Byte.valueOf(1),
            boxedDouble = java.lang.Double.valueOf(1.1),
            boxedFloat = java.lang.Float.valueOf(1.2.toFloat()),
            boxedShort = java.lang.Short.valueOf(2)
        )
        anyValues("123", null)
        anyValues(1, null)
        anyValues(3.2, null)
    }

    private fun primitiveValues(
        d: Double,
        f: Float,
        l: Long,
        i: Int,
        b: Boolean,
        c: Char,
        s: Short,
    ) {
        counter.incrementAndGet()
    }

    private fun boxedValues(
        boxedInt: Int?,
        boxedBoolean: Boolean?,
        boxedChar: Char?,
        boxedShort: Short?,
        boxedByte: Byte?,
        boxedDouble: Double?,
        boxedFloat: Float?,
        boxedLong: Long?,
    ) {
        counter.decrementAndGet()
    }

    private fun anyValues(
        any: Any,
        expectedNull: Any?,
    ) {
        counter.get()
    }


    @Test
    fun test() = ModelCheckingOptions().check(this::class)

}