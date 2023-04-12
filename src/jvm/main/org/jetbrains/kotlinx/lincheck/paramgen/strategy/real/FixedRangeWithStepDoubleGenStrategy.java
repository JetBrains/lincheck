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

import org.jetbrains.kotlinx.lincheck.RandomFactory;

import java.util.Random;

public class FixedRangeWithStepDoubleGenStrategy implements RandomDoubleGenStrategy {

    protected final Random random = RandomFactory.INSTANCE.createRandom();

    private final double delta;
    private final double begin;
    private final int maxSteps;

    public FixedRangeWithStepDoubleGenStrategy(double begin, double end, double step) {
        this.begin = begin;
        this.delta = end - begin;

        if (delta / step > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Supplied step: " + step + " is to small to specified range");
        }

        maxSteps = (int) (delta / step);
    }

    @Override
    public double nextDouble() {
        return begin + delta * random.nextInt(maxSteps + 1);
    }
}
