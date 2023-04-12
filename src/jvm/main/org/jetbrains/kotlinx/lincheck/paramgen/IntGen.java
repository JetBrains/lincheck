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

import org.jetbrains.kotlinx.lincheck.paramgen.strategy.integer.ExpandingRangeIntGenStrategy;
import org.jetbrains.kotlinx.lincheck.paramgen.strategy.integer.FixedRangeIntGenStrategy;
import org.jetbrains.kotlinx.lincheck.paramgen.strategy.integer.RandomIntGenStrategy;

public class IntGen implements ParameterGenerator<Integer> {
    private static final int LOWER_BOUND_MIN = -100;
    private static final int UPPER_BOUND_MAX = 100;

    private final RandomIntGenStrategy genStrategy;

    public IntGen(String configuration) {
        if (configuration.isEmpty()) { // use default configuration
            genStrategy = new ExpandingRangeIntGenStrategy(0, 0, LOWER_BOUND_MIN, UPPER_BOUND_MAX);
            return;
        }

        genStrategy = new FixedRangeIntGenStrategy(configuration, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    public Integer generate() {
        return genStrategy.nextInt();
    }

}
