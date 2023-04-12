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

public class FixedRangeIntGenStrategy implements RandomIntGenStrategy {

    protected final Random random = new Random(0);
    private final int rangeLowerBoundInclusive;
    private final int rangeUpperBoundInclusive;

    public FixedRangeIntGenStrategy(String configuration, int minRangeLowerBound, int maxRangeUpperBound, String type) {
        if (configuration.isEmpty()) {
            throw new IllegalArgumentException("Configuration can't be empty");
        }
        String[] args = configuration.replaceAll("\\s", "").split(":");
        if (args.length == 2) { // begin:end
            rangeLowerBoundInclusive = Integer.parseInt(args[0]);
            rangeUpperBoundInclusive = Integer.parseInt(args[1]);
            checkRange(minRangeLowerBound, maxRangeUpperBound, type);
        } else {
            throw new IllegalArgumentException("Configuration should have " +
                    "two arguments (begin and end) separated by colon");
        }
    }

    private void checkRange(int min, int max, String type) {
        if (this.rangeLowerBoundInclusive < min || this.rangeUpperBoundInclusive - 1 > max) {
            throw new IllegalArgumentException("Illegal range for "
                    + type + " type: [" + rangeLowerBoundInclusive + "; " + rangeUpperBoundInclusive + ")");
        }
    }

    @Override
    public int nextInt() {
        return generateFromRandomRange(random, rangeLowerBoundInclusive, rangeUpperBoundInclusive);
    }
}
