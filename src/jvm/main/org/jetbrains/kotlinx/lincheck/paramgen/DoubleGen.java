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

import org.jetbrains.kotlinx.lincheck.paramgen.strategy.real.ExpandingDoubleRangeGenStrategy;
import org.jetbrains.kotlinx.lincheck.paramgen.strategy.real.FixedRangeDoubleGenStrategy;
import org.jetbrains.kotlinx.lincheck.paramgen.strategy.real.FixedRangeWithStepDoubleGenStrategy;
import org.jetbrains.kotlinx.lincheck.paramgen.strategy.real.RandomDoubleGenStrategy;

public class DoubleGen implements ParameterGenerator<Double> {
    private static final float DEFAULT_STEP = 0.1f;
    private final RandomDoubleGenStrategy genStrategy;

    public DoubleGen(String configuration) {
        if (configuration.isEmpty()) { // use default configuration
            genStrategy = new ExpandingDoubleRangeGenStrategy(10, DEFAULT_STEP);
            return;
        }
        String[] args = configuration.replaceAll("\\s", "").split(":");

        double begin;
        double end;
        switch (args.length) {
            case 2: // begin:end
                begin = Double.parseDouble(args[0]);
                end = Double.parseDouble(args[1]);
                genStrategy = new FixedRangeWithStepDoubleGenStrategy(begin, end, DEFAULT_STEP);
                break;
            case 3: // begin:step:end
                begin = Double.parseDouble(args[0]);
                double step = Double.parseDouble(args[1]);
                end = Double.parseDouble(args[2]);
                if (step == 0.0) {
                    genStrategy = new FixedRangeDoubleGenStrategy(begin, end);
                } else {
                    genStrategy = new FixedRangeWithStepDoubleGenStrategy(begin, end, DEFAULT_STEP);
                }
                break;
            default:
                throw new IllegalArgumentException("Configuration should have two (begin and end) " + "or three (begin, step and end) arguments separated by colon");
        }

    }

    public Double generate() {
        return genStrategy.nextDouble();
    }
}
