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

import org.jetbrains.kotlinx.lincheck.paramgen.ExpandingRangeIntGenerator
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ExpandingRangeGeneratorTest {

    @Test
    fun `generator should expand generated values range and than generate values from it`() {
        val generator = ExpandingRangeIntGenerator(Random(0), Int.MIN_VALUE, Int.MAX_VALUE)

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