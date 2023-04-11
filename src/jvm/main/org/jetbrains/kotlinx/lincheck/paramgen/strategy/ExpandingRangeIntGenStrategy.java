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

package org.jetbrains.kotlinx.lincheck.paramgen.strategy;

public class ExpandingRangeIntGenStrategy extends RandomIntGenStrategy {

    private int currentRangeLowerBoundInclusive;
    private int currentRangeUpperBoundInclusive;

    private final int maxRangeLowerBoundInclusive;
    private final int minRangeUpperBoundInclusive;

    private NextExpansionDirection nextExpansionDirection = NextExpansionDirection.UPPER_BOUND;

    public ExpandingRangeIntGenStrategy(int currentRangeLowerBoundInclusive, int currentRangeUpperBoundInclusive, int maxRangeLowerBoundInclusive, int minRangeUpperBoundInclusive) {
        if (currentRangeUpperBoundInclusive - currentRangeLowerBoundInclusive + 1 < 0) {
            throw new IllegalArgumentException("rangeUpperBoundInclusive must be >= than rangeLowerBoundInclusive");
        }
        if (maxRangeLowerBoundInclusive - minRangeUpperBoundInclusive + 1 < 0) {
            throw new IllegalArgumentException("rangeUpperBoundInclusive must be >= than rangeLowerBoundInclusive");
        }
        if (currentRangeUpperBoundInclusive > maxRangeLowerBoundInclusive) {
            throw new IllegalArgumentException("currentRangeUpperBoundInclusive must be <= than maxRangeLowerBoundInclusive");
        }
        if (currentRangeLowerBoundInclusive < maxRangeLowerBoundInclusive) {
            throw new IllegalArgumentException("currentRangeLowerBoundInclusive must be >= than maxRangeLowerBoundInclusive");
        }

        this.currentRangeLowerBoundInclusive = currentRangeLowerBoundInclusive;
        this.currentRangeUpperBoundInclusive = currentRangeUpperBoundInclusive;
        this.maxRangeLowerBoundInclusive = maxRangeLowerBoundInclusive;
        this.minRangeUpperBoundInclusive = minRangeUpperBoundInclusive;
    }


    @Override
    public int nextInt() {
        checkRangeExpansionAbility();

        if (nextExpansionDirection == NextExpansionDirection.DISABLED) {
            return generateFromRandomRange(currentRangeLowerBoundInclusive, currentRangeUpperBoundInclusive);
        }

        boolean expandRange = random.nextBoolean();
        if (!expandRange) {
            return generateFromRandomRange(currentRangeLowerBoundInclusive, currentRangeUpperBoundInclusive);
        }
        if (nextExpansionDirection == NextExpansionDirection.LOWER_BOUND) {
            nextExpansionDirection = NextExpansionDirection.UPPER_BOUND;
            currentRangeLowerBoundInclusive--;
        } else {
            nextExpansionDirection = NextExpansionDirection.LOWER_BOUND;
            currentRangeUpperBoundInclusive++;
        }

        return generateFromRandomRange(currentRangeLowerBoundInclusive, currentRangeUpperBoundInclusive);
    }

    private void checkRangeExpansionAbility() {
        if (currentRangeUpperBoundInclusive == maxRangeLowerBoundInclusive && currentRangeLowerBoundInclusive == minRangeUpperBoundInclusive) {
            nextExpansionDirection = NextExpansionDirection.DISABLED;
            return;
        }
        if (currentRangeLowerBoundInclusive == minRangeUpperBoundInclusive) {
            nextExpansionDirection = NextExpansionDirection.UPPER_BOUND;
            return;
        }
        if (currentRangeUpperBoundInclusive == maxRangeLowerBoundInclusive) {
            nextExpansionDirection = NextExpansionDirection.LOWER_BOUND;
        }
    }

    private enum NextExpansionDirection {
        UPPER_BOUND,
        LOWER_BOUND,
        DISABLED
    }


}
