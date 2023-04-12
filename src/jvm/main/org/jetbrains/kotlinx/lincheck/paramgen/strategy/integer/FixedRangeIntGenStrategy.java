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

public class FixedRangeIntGenStrategy implements RandomIntGenStrategy {

    protected final Random random = RandomFactory.INSTANCE.createRandom();
    private final int begin;
    private final int end;

    public FixedRangeIntGenStrategy(String configuration, int meinBegin, int maxEnd, String type) {
        if (configuration.isEmpty()) {
            throw new IllegalArgumentException("Configuration can't be empty");
        }
        String[] args = configuration.replaceAll("\\s", "").split(":");

        if (args.length != 2) {
            throw new IllegalArgumentException("Configuration should have " +
                    "two arguments (begin and end) separated by colon");
        }
        // begin:end
        begin = Integer.parseInt(args[0]);
        end = Integer.parseInt(args[1]);
        checkRange(meinBegin, maxEnd, type);
    }

    private void checkRange(int min, int max, String type) {
        if (this.begin < min || this.end - 1 > max) {
            throw new IllegalArgumentException("Illegal range for "
                    + type + " type: [" + begin + "; " + end + ")");
        }
    }

    @Override
    public int nextInt() {
        return begin + random.nextInt(end - begin + 1);
    }

}
