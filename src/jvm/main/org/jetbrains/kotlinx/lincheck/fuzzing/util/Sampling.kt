/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.fuzzing.util

import java.util.*
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln

object Sampling {
    fun samplePoisson(random: Random, lambda: Double): Int {
        val L = exp(-lambda)
        var p = 1.0
        var k = 0
        do {
            k++
            p *= random.nextDouble()
        } while (p > L)
        return k - 1
    }

    /**
     * Sample from a geometric distribution with given mean.
     *
     * Utility method used in implementing mutation operations.
     *
     * @param random a pseudo-random number generator
     * @param mean the mean of the distribution
     * @return a randomly sampled value
     */
    fun sampleGeometric(random: Random, mean: Double): Int {
        val p = 1 / mean
        val uniform = random.nextDouble()
        return ceil(ln(1 - uniform) / ln(1 - p)).toInt()
    }
}