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

import org.jetbrains.kotlinx.lincheck.RandomFactory;

import java.util.Random;

public class ExpandingRangeIntGenStrategy implements RandomIntGenStrategy {

    protected final Random random = RandomFactory.INSTANCE.createRandom();

    private int shiftLeft;
    private int length;
    private final int maxLength;

    /**
     * @param maxRadius  max radius of expanded range. For example if 4, it will generate a value in [-4, 4] bounds
     */
    public ExpandingRangeIntGenStrategy(int maxRadius) {
        if (maxRadius < 0) {
            throw new IllegalArgumentException("maxRadius must be >= 0");
        }

        shiftLeft = 0;
        length = 1;
        maxLength = maxRadius * 2 + 1;
    }


    @Override
    public int nextInt() {
        if (length == maxLength) {
            return shiftLeft + random.nextInt(length);
        }
        if (length % 2 != 0) {
            shiftLeft++;
        }
        length++;

        return shiftLeft + random.nextInt(length);
    }


}
