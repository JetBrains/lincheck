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

public class FixedRangeIntGenStrategy extends RandomIntGenStrategy {

    private final int rangeLowerBoundInclusive;
    private final int rangeUpperBoundInclusive;

    public FixedRangeIntGenStrategy(int rangeLowerBoundInclusive, int rangeUpperBoundInclusive) {
        if (rangeUpperBoundInclusive - rangeLowerBoundInclusive + 1 < 0) {
            throw new IllegalArgumentException("rangeUpperBoundInclusive must be >= than rangeLowerBoundInclusive");
        }
        this.rangeLowerBoundInclusive = rangeLowerBoundInclusive;
        this.rangeUpperBoundInclusive = rangeUpperBoundInclusive;
    }

    @Override
    public int nextInt() {
        return generateFromRandomRange(rangeLowerBoundInclusive, rangeUpperBoundInclusive);
    }
}
