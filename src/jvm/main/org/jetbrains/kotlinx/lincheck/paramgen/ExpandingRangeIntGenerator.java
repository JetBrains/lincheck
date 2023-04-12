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

package org.jetbrains.kotlinx.lincheck.paramgen;

import java.util.Random;

/**
 * Represents a random number generator from specified extensible range.
 * Uses two ranges as parameters: initial and maximal range.
 * After each generation takes an arbitrary number from the current range and with 50% probability expands its
 * right or left bound. This process continues until current range bounds are equals to maximal range bounds.
 */
public class ExpandingRangeIntGenerator {
    private final Random random;

    private static final int DEFAULT_START_VALUE = 0;

    /**
     * Left (inclusive) bound of current range
     */
    private int begin;
    /**
     * Right (inclusive) bound of current range
     */
    private int end;

    /**
     * Minimal left (inclusive) bound of current range
     */
    private final int minBegin;
    /**
     * Maximal right (inclusive) bound of current range
     */
    private final int maxEnd;

    private NextExpansionDirection nextExpansionDirection = NextExpansionDirection.UP;

    /**
     * Creates extensible range-based random generator.
     *
     * @param random   random for this generator
     * @param begin    initial range left bound (inclusive)
     * @param end      initial range right bound (inclusive)
     * @param minBegin minimal range left bound (inclusive)
     * @param maxEnd   maximal range right bound (inclusive)
     */
    public ExpandingRangeIntGenerator(Random random, int begin, int end, int minBegin, int maxEnd) {
        this.random = random;
        this.begin = begin;
        this.end = end;
        this.minBegin = minBegin;
        this.maxEnd = maxEnd;
    }


    /**
     * Generates next random number.
     * With 50% percent probability expands current range if can and returns an expanded bound as a value.
     * Otherwise, returns a random value from the current range
     *
     * @return random value from current range or moved bound
     */
    public int nextInt() {
        checkRangeExpansionAbility();

        if (nextExpansionDirection == NextExpansionDirection.DISABLED || !random.nextBoolean()) {
            return generateFromRandomRange(begin, end);
        }

        if (nextExpansionDirection == NextExpansionDirection.DOWN) {
            nextExpansionDirection = NextExpansionDirection.UP;
            return --begin;
        } else {
            nextExpansionDirection = NextExpansionDirection.DOWN;
            return ++end;
        }
    }

    private void checkRangeExpansionAbility() {
        if (begin == minBegin && end == maxEnd) {
            nextExpansionDirection = NextExpansionDirection.DISABLED;
            return;
        }
        if (begin == minBegin) {
            nextExpansionDirection = NextExpansionDirection.UP;
            return;
        }
        if (end == maxEnd) {
            nextExpansionDirection = NextExpansionDirection.DOWN;
        }
    }

    private enum NextExpansionDirection {
        UP,
        DOWN,
        DISABLED
    }

    private int generateFromRandomRange(int rangeLowerBound, int rangeUpperBound) {
        return rangeLowerBound + random.nextInt(rangeUpperBound - rangeLowerBound + 1);
    }

    /**
     * Creates extensible range-based random generator.
     *
     * @param random        random for this generator
     * @param configuration range configuration in format begin:end, may be empty
     * @param minBegin      minimal range left bound (inclusive)
     * @param maxEnd        maximal range right bound (inclusive)
     * @param type          name of current type for generation for exception message if such appears
     * @return created range-based random generator§
     */
    public static ExpandingRangeIntGenerator create(Random random, String configuration, int minBegin, int maxEnd, String type) {
        if (configuration.isEmpty()) {
            return new ExpandingRangeIntGenerator(random, DEFAULT_START_VALUE, DEFAULT_START_VALUE, minBegin, maxEnd);
        }

        String[] args = configuration.replaceAll("\\s", "").split(":");
        if (args.length != 2) {
            throw new IllegalArgumentException("Configuration should have " +
                    "two arguments (begin and end) separated by colon");
        }

        // begin:end
        int begin = Integer.parseInt(args[0]);
        int end = Integer.parseInt(args[1]);
        if (begin < minBegin || end - 1 > maxEnd) {
            throw new IllegalArgumentException("Illegal range for "
                    + type + " type: [" + begin + "; " + end + ")");
        }

        int startValue = begin + (end - begin) / 2;
        if (end < begin) {
            throw new IllegalArgumentException("end must be >= than begin");
        }
        if (maxEnd < minBegin) {
            throw new IllegalArgumentException("maxEnd must be >= than minBegin");
        }
        if (end > maxEnd) {
            throw new IllegalArgumentException("end must be <= than maxEnd");
        }

        return new ExpandingRangeIntGenerator(random, startValue, startValue, begin, end);
    }


}
