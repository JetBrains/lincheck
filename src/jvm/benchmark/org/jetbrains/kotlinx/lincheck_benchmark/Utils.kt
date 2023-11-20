/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_benchmark

import kotlin.math.round
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit

fun Iterable<LongArray>.flatten(): LongArray {
    val size = sumOf { it.size }
    val result = LongArray(size)
    var i = 0
    for (array in this) {
        for (element in array) {
            result[i++] = element
        }
    }
    return result
}

fun LongArray.convertTo(unit: DurationUnit) {
    for (i in indices) {
        this[i] = this[i].nanoseconds.toLong(unit)
    }
}

fun LongArray.standardDeviation(): Double {
    val mean = round(average()).toLong()
    var variance = 0L
    for (x in this) {
        val d = x - mean
        variance += d * d
    }
    return sqrt(variance.toDouble() / (size - 1))
}

fun LongArray.standardError(): Double {
    return standardDeviation() / sqrt(size.toDouble())
}