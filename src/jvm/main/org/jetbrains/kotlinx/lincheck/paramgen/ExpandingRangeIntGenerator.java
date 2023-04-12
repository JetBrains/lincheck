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

public class ExpandingRangeIntGenerator {
    private final Random random;

    private static final int DEFAULT_START_VALUE = 0;

    private int begin;
    private int end;

    private final int minBegin;
    private final int maxEnd;

    private NextExpansionDirection nextExpansionDirection = NextExpansionDirection.UPPER_BOUND;

    public ExpandingRangeIntGenerator(Random random, int begin, int end, int minBegin, int maxEnd) {
        this.random = random;
        this.begin = begin;
        this.end = end;
        this.minBegin = minBegin;
        this.maxEnd = maxEnd;
    }


    public int nextInt() {
        checkRangeExpansionAbility();

        if (nextExpansionDirection == NextExpansionDirection.DISABLED || !random.nextBoolean()) {
            return generateFromRandomRange(begin, end);
        }

        if (nextExpansionDirection == NextExpansionDirection.LOWER_BOUND) {
            nextExpansionDirection = NextExpansionDirection.UPPER_BOUND;
            return --begin;
        } else {
            nextExpansionDirection = NextExpansionDirection.LOWER_BOUND;
            return ++end;
        }
    }

    private void checkRangeExpansionAbility() {
        if (begin == minBegin && end == maxEnd) {
            nextExpansionDirection = NextExpansionDirection.DISABLED;
            return;
        }
        if (begin == minBegin) {
            nextExpansionDirection = NextExpansionDirection.UPPER_BOUND;
            return;
        }
        if (end == maxEnd) {
            nextExpansionDirection = NextExpansionDirection.LOWER_BOUND;
        }
    }

    private enum NextExpansionDirection {
        UPPER_BOUND,
        LOWER_BOUND,
        DISABLED
    }

    private int generateFromRandomRange(int rangeLowerBound, int rangeUpperBound) {
        return rangeLowerBound + random.nextInt(rangeUpperBound - rangeLowerBound + 1);
    }

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
