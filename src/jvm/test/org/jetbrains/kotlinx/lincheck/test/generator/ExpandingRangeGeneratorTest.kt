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

import org.jetbrains.kotlinx.lincheck.paramgen.ExpandingRangeIntGenerator
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ExpandingRangeGeneratorTest {

    @Test
    fun `generator should expand generated values range and than generate values from it`() {
        val generator = ExpandingRangeIntGenerator(Random(0), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)

        val batchesCount = 50
        val batchSize = 10_000
        val batchInfos = List(batchesCount) {
            val batch = List(batchSize) { generator.nextInt() }
            val averageNegative = batch.filter { it <= 0 }.average()
            val averagePositive = batch.filter { it >= 0 }.average()

            RandomValuesBatchInfo(averageNegative, averagePositive)
        }

        batchInfos.windowed(2).forEach { (prevBatch, nextBatch) ->
            println(nextBatch)
            assertTrue(nextBatch.averageNegative < prevBatch.averageNegative)
            assertTrue(nextBatch.averagePositive > prevBatch.averagePositive)
        }
    }

    private data class RandomValuesBatchInfo(
        val averageNegative: Double,
        val averagePositive: Double
    )


}