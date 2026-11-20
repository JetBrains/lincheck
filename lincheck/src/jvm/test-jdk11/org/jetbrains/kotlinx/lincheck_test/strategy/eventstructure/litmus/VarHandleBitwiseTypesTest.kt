/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmus

import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSame
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.invoke.MethodHandles

@RunWith(Parameterized::class)
class VarHandleBitwiseTypesTest(private val mode: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "mode={0}")
        fun modes() = (0..8).map { arrayOf(it) }

        private val charHandle = MethodHandles.arrayElementVarHandle(CharArray::class.java)
        private val booleanHandle = MethodHandles.arrayElementVarHandle(BooleanArray::class.java)
    }

    @Test
    fun charAndBooleanOperands() {
        val accessMode = mode
        val expectedChar = when (accessMode / 3) {
            0 -> 0x8001
            1 -> 0x8007
            else -> 6
        }
        val expectedBooleans = when (accessMode / 3) {
            0 -> 0b0001
            1 -> 0b0111
            else -> 0b0110
        }
        val expected = Triple(0x8003, expectedChar, 0b0101 or (expectedBooleans shl 4))
        litmusTest(assertSame(setOf(expected)), requireExhaustion = true) {
            val chars = charArrayOf('\u8003')
            val booleans = booleanArrayOf(true, false, true, false)
            val oldChar = when (accessMode) {
                0 -> charHandle.getAndBitwiseAnd(chars, 0, '\u8005') as Char
                1 -> charHandle.getAndBitwiseAndAcquire(chars, 0, '\u8005') as Char
                2 -> charHandle.getAndBitwiseAndRelease(chars, 0, '\u8005') as Char
                3 -> charHandle.getAndBitwiseOr(chars, 0, '\u8005') as Char
                4 -> charHandle.getAndBitwiseOrAcquire(chars, 0, '\u8005') as Char
                5 -> charHandle.getAndBitwiseOrRelease(chars, 0, '\u8005') as Char
                6 -> charHandle.getAndBitwiseXor(chars, 0, '\u8005') as Char
                7 -> charHandle.getAndBitwiseXorAcquire(chars, 0, '\u8005') as Char
                else -> charHandle.getAndBitwiseXorRelease(chars, 0, '\u8005') as Char
            }
            // Low four bits encode the returned values; high four bits encode the updated values.
            var booleanResults = 0
            for (index in 0..3) {
                val oldBoolean = when (accessMode) {
                    0 -> booleanHandle.getAndBitwiseAnd(booleans, index, index < 2) as Boolean
                    1 -> booleanHandle.getAndBitwiseAndAcquire(booleans, index, index < 2) as Boolean
                    2 -> booleanHandle.getAndBitwiseAndRelease(booleans, index, index < 2) as Boolean
                    3 -> booleanHandle.getAndBitwiseOr(booleans, index, index < 2) as Boolean
                    4 -> booleanHandle.getAndBitwiseOrAcquire(booleans, index, index < 2) as Boolean
                    5 -> booleanHandle.getAndBitwiseOrRelease(booleans, index, index < 2) as Boolean
                    6 -> booleanHandle.getAndBitwiseXor(booleans, index, index < 2) as Boolean
                    7 -> booleanHandle.getAndBitwiseXorAcquire(booleans, index, index < 2) as Boolean
                    else -> booleanHandle.getAndBitwiseXorRelease(booleans, index, index < 2) as Boolean
                }
                if (oldBoolean) booleanResults = booleanResults or (1 shl index)
                if (booleans[index]) booleanResults = booleanResults or (1 shl (index + 4))
            }
            Triple(oldChar.code, chars[0].code, booleanResults)
        }
    }
}
