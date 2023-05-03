/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
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
