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

package org.jetbrains.kotlinx.lincheck.paramgen.strategy.real;

import org.jetbrains.kotlinx.lincheck.paramgen.strategy.integer.ExpandingRangeIntGenStrategy;

public class ExpandingDoubleRangeGenStrategy implements RandomDoubleGenStrategy {

    private final ExpandingRangeIntGenStrategy intGenStrategy;
    private final double step;
    private final double begin;

    /**
     * @param maxRadius  max radius of expanded range, for example if 4.0, it will generate a value in (-4.0, 4.0) bounds
     * @param step step of this range
     */
    public ExpandingDoubleRangeGenStrategy(double maxRadius, double step) {
        this.step = step;
        double delta = 2 * maxRadius;
        this.begin = -maxRadius;

        if (delta / step > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Supplied step: " + step + " is to small to specified range");
        }

        int maxSteps = (int) (delta / step);
        int intRadius = maxSteps / 2;

        intGenStrategy = new ExpandingRangeIntGenStrategy(intRadius);
    }

    @Override
    public double nextDouble() {
        return begin + intGenStrategy.nextInt() * step;
    }
}
