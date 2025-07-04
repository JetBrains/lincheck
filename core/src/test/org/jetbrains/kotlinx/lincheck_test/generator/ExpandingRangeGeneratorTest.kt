/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.generator

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.jetbrains.lincheck.datastructures.ExpandingRangeIntGenerator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

// This test should be isolated, as the mockK library
// may change some coroutine test output
// (though, have no idea how exactly).
class ExpandingRangeGeneratorIsolatedTest {

    @Test
    fun `generator should expand generated values range and than generate values from it`() {
        val begin = 7
        val end = 12
        val listOfRangeValues = (begin..end).toList()
        var nextRangeIndex = 0
        val argument = slot<Int>()
        val validRandomNextIntMethodRange = (0..6)
        val mockedRandom = mockk<Random> {
            every { nextDouble() } returns 1.0
            every { nextInt(capture(argument)) } answers {
                check(argument.captured in validRandomNextIntMethodRange) { "Request too big range from random" }
                listOfRangeValues[nextRangeIndex++] - begin
            }
        }
        val generator = ExpandingRangeIntGenerator(mockedRandom, begin, end)

        // Checking that range is expanded
        val generatedValues = (0 until 5).map { generator.nextInt() }
        assertEquals("Bad generated range", listOf(10, 8, 11, 7, 12), generatedValues)

        // Checking that all values after expansion are in the bound
        val restGeneratedValues = listOfRangeValues.map { generator.nextInt() }
        assertEquals(listOfRangeValues, restGeneratedValues)
    }


}