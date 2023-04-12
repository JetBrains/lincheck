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

package org.jetbrains.kotlinx.lincheck.paramgen.strategy.integer;

import java.util.Random;

public class ExpandingRangeIntGenStrategy implements RandomIntGenStrategy {

    protected final Random random = new Random(0);

    private int currentRangeLowerBound;
    private int currentRangeUpperBound;

    private final int maxRangeLowerBound;
    private final int minRangeUpperBound;

    private NextExpansionDirection nextExpansionDirection = NextExpansionDirection.UPPER_BOUND;

    public ExpandingRangeIntGenStrategy(int currentRangeLowerBound, int currentRangeUpperBound, int minRangeLowerBound, int maxRangeUpperBound) {
        if (currentRangeUpperBound < currentRangeLowerBound) {
            throw new IllegalArgumentException("rangeUpperBound must be >= than rangeLowerBound");
        }
        if (maxRangeUpperBound < minRangeLowerBound) {
            throw new IllegalArgumentException("rangeUpperBound must be >= than rangeLowerBound");
        }
        if (currentRangeUpperBound > maxRangeUpperBound) {
            throw new IllegalArgumentException("currentRangeUpperBound must be <= than minRangeLowerBound");
        }
        if (currentRangeLowerBound < minRangeLowerBound) {
            throw new IllegalArgumentException("currentRangeLowerBound must be >= than minRangeLowerBound");
        }

        this.currentRangeLowerBound = currentRangeLowerBound;
        this.currentRangeUpperBound = currentRangeUpperBound;
        this.maxRangeLowerBound = minRangeLowerBound;
        this.minRangeUpperBound = maxRangeUpperBound;
    }


    @Override
    public int nextInt() {
        checkRangeExpansionAbility();

        if (nextExpansionDirection == NextExpansionDirection.DISABLED) {
            return generateFromRandomRange(random, currentRangeLowerBound, currentRangeUpperBound);
        }

        boolean expandRange = random.nextBoolean();
        if (expandRange) {
            if (nextExpansionDirection == NextExpansionDirection.LOWER_BOUND) {
                nextExpansionDirection = NextExpansionDirection.UPPER_BOUND;
                currentRangeLowerBound--;
            } else {
                nextExpansionDirection = NextExpansionDirection.LOWER_BOUND;
                currentRangeUpperBound++;
            }
        }

        return generateFromRandomRange(random, currentRangeLowerBound, currentRangeUpperBound);
    }

    private void checkRangeExpansionAbility() {
        if (currentRangeUpperBound == maxRangeLowerBound && currentRangeLowerBound == minRangeUpperBound) {
            nextExpansionDirection = NextExpansionDirection.DISABLED;
            return;
        }
        if (currentRangeLowerBound == minRangeUpperBound) {
            nextExpansionDirection = NextExpansionDirection.UPPER_BOUND;
            return;
        }
        if (currentRangeUpperBound == maxRangeLowerBound) {
            nextExpansionDirection = NextExpansionDirection.LOWER_BOUND;
        }
    }

    private enum NextExpansionDirection {
        UPPER_BOUND,
        LOWER_BOUND,
        DISABLED
    }


}
