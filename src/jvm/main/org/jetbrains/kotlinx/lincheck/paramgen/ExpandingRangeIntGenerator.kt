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

package org.jetbrains.kotlinx.lincheck.paramgen

import java.util.*


/**
 * Extensible range-based random generator.
 *
 * @param random   random for this generator
 * @param startInclusive    initial range left bound (inclusive)
 * @param endInclusive      initial range right bound (inclusive)
 * @param minStartInclusive minimal range left bound (inclusive)
 * @param maxEndInclusive   maximal range right bound (inclusive)
 */
class ExpandingRangeIntGenerator(
    private val random: Random,
    private val startInclusive: Int,
    private val endInclusive: Int,
    private val minStartInclusive: Int,
    private val maxEndInclusive: Int
) {

    /**
     * Current left (inclusive) bound of current range
     */
    private var currentStartInclusive: Int

    /**
     * Current right (inclusive) bound of current range
     */
    private var currentEndInclusive: Int

    private var nextExpansionDirection = NextExpansionDirection.UP


    init {
        currentStartInclusive = startInclusive
        currentEndInclusive = endInclusive
    }

    /**
     * Reset bounds of this expanding range
     */
    fun reset() {
        currentStartInclusive = startInclusive
        currentEndInclusive = endInclusive
    }

    /**
     * Generates next random number.
     * With 50% percent probability expands current range if can and returns an expanded bound as a value.
     * It alternates expansion direction from left to right.
     * Otherwise, returns a random value from the current range.
     *
     * @return random value from current range or moved bound
     */
    fun nextInt(): Int {
        checkRangeExpansionAbility()
        if (nextExpansionDirection == NextExpansionDirection.DISABLED || !random.nextBoolean()) {
            return generateFromRandomRange(currentStartInclusive, currentEndInclusive)
        }

        return if (nextExpansionDirection == NextExpansionDirection.DOWN) {
            nextExpansionDirection = NextExpansionDirection.UP
            --currentStartInclusive
        } else {
            nextExpansionDirection = NextExpansionDirection.DOWN
            ++currentEndInclusive
        }
    }

    private fun checkRangeExpansionAbility() {
        if (currentStartInclusive == minStartInclusive && currentEndInclusive == maxEndInclusive) {
            nextExpansionDirection = NextExpansionDirection.DISABLED
            return
        }
        if (currentStartInclusive == minStartInclusive) {
            nextExpansionDirection = NextExpansionDirection.UP
            return
        }
        if (currentEndInclusive == maxEndInclusive) {
            nextExpansionDirection = NextExpansionDirection.DOWN
        }
    }

    private enum class NextExpansionDirection {
        UP,
        DOWN,
        DISABLED
    }

    private fun generateFromRandomRange(rangeLowerBoundInclusive: Int, rangeUpperBoundInclusive: Int): Int {
        return rangeLowerBoundInclusive + random.nextInt(rangeUpperBoundInclusive - rangeLowerBoundInclusive + 1)
    }

}

/**
 * Factory method to create extensible range with string configuration
 *
 * @param random            random for this generator
 * @param configuration     string configuration of format startInclusive:endInclusive
 * @param minStartInclusive minimal range left bound (inclusive)
 * @param maxEndInclusive   maximal range right bound (inclusive)
 * @param type              type of generator which is using this method to throw exception with this type name im message
 */
internal fun ExpandingRangeIntGenerator(
    random: Random, configuration: String, minStartInclusive: Int, maxEndInclusive: Int, type: String
): ExpandingRangeIntGenerator {
    if (configuration.isEmpty()) return ExpandingRangeIntGenerator(
        random = random,
        startInclusive = DEFAULT_START_VALUE,
        endInclusive = DEFAULT_START_VALUE,
        minStartInclusive = minStartInclusive,
        maxEndInclusive = maxEndInclusive
    )

    // start:end
    val args = configuration.replace("\\s", "").split(":")

    require(args.size == 2) { "Configuration should have two arguments (start and end) separated by colon" }

    val startInclusive =
        args[0].toIntOrNull() ?: error("Bad $type configuration. StartInclusive value must be a valid integer.")
    val endInclusive =
        args[1].toIntOrNull() ?: error("Bad $type configuration. EndInclusive value must be a valid integer.")
    require(startInclusive >= minStartInclusive || endInclusive - 1 <= maxEndInclusive) { "Illegal range for $type type: [$startInclusive; $endInclusive)" }

    val startValue = startInclusive + (endInclusive - startInclusive) / 2
    require(endInclusive >= startInclusive) { "end must be >= than start" }
    require(maxEndInclusive >= minStartInclusive) { "maxEnd must be >= than minStart" }
    require(endInclusive <= maxEndInclusive) { "end must be <= than maxEnd" }

    return ExpandingRangeIntGenerator(
        random = random,
        startInclusive = startValue,
        endInclusive = startValue,
        minStartInclusive = startInclusive,
        maxEndInclusive = endInclusive
    )
}

private const val DEFAULT_START_VALUE = 0