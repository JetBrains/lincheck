package org.jetbrains.kotlinx.lincheck.paramgen;

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import org.jetbrains.kotlinx.lincheck.paramgen.strategy.ExpandingRangeIntGenStrategy;
import org.jetbrains.kotlinx.lincheck.paramgen.strategy.FixedRangeIntGenStrategy;
import org.jetbrains.kotlinx.lincheck.paramgen.strategy.RandomIntGenStrategy;

public class IntGen implements ParameterGenerator<Integer> {

    private final RandomIntGenStrategy intGenStrategy;

    public IntGen(int rangeLowerBoundInclusive, int rangeUpperBoundInclusive) {
        intGenStrategy = new FixedRangeIntGenStrategy(rangeLowerBoundInclusive, rangeUpperBoundInclusive);
    }

    public IntGen(int currentRangeLowerBoundInclusive, int currentRangeUpperBoundInclusive, int minRangeLowerBoundInclusive, int maxRangeUpperBoundInclusive) {
        intGenStrategy = new ExpandingRangeIntGenStrategy(currentRangeLowerBoundInclusive, currentRangeUpperBoundInclusive, minRangeLowerBoundInclusive, maxRangeUpperBoundInclusive);
    }

    @Override
    public Integer generate() {
        return intGenStrategy.nextInt();
    }
}
