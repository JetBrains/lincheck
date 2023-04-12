/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.generator

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.jetbrains.kotlinx.lincheck.paramgen.ExpandingRangeIntGenerator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class ExpandingRangeGeneratorTest {

    @Test
    fun `generator should expand generated values range and than generate values from it`() {
        val begin = 7
        val end = 12
        val listOfRangeValues = (begin..end).toList()
        var nextRangeIndex = 0
        val argument = slot<Int>()
        val validRandomNextIntMethodRange = (0 .. 6)
        val mockedRandom = mockk<Random>() {
            every { nextBoolean() } returns true
            every { nextInt(capture(argument)) } answers {
                check(argument.captured in validRandomNextIntMethodRange) { "Request too big range from random" }
                listOfRangeValues[nextRangeIndex++] - begin
            }
        }
        val generator = ExpandingRangeIntGenerator(mockedRandom, 9, 9, begin, end)

        // Checking that range is expanded
        val generatedValues = (0 until 5).map { generator.nextInt() }
        assertEquals("Bad generated range", listOf(10, 8, 11, 7, 12), generatedValues)

        // Checking that all values after expansion are in the bound
        val restGeneratedValues = listOfRangeValues.map { generator.nextInt() }
        assertEquals(listOfRangeValues, restGeneratedValues)
    }


}