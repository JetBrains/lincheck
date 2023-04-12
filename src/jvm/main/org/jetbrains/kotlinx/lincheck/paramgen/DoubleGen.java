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

import org.jetbrains.kotlinx.lincheck.RandomProvider;

public class DoubleGen implements ParameterGenerator<Double> {
    private static final float DEFAULT_STEP = 0.1f;
    private static final float DEFAULT_BEGIN = -10f;
    private static final float DEFAULT_END = 10f;

    private final ExpandingRangeIntGenerator intGenerator;
    private final double step;
    private final double begin;

    public DoubleGen(RandomProvider randomProvider, String configuration) {
        double begin;
        double end;
        double step = 0.0;

        if (configuration.isEmpty()) { // use default configuration
            begin = DEFAULT_BEGIN;
            end = DEFAULT_END;
            step = DEFAULT_STEP;
        } else {
            String[] args = configuration.replaceAll("\\s", "").split(":");

            switch (args.length) {
                case 2: // begin:end
                    begin = Double.parseDouble(args[0]);
                    end = Double.parseDouble(args[1]);
                    break;
                case 3: // begin:step:end
                    begin = Double.parseDouble(args[0]);
                    step = Double.parseDouble(args[1]);
                    end = Double.parseDouble(args[2]);
                    break;
                default:
                    throw new IllegalArgumentException("Configuration should have two (begin and end) " + "or three (begin, step and end) arguments separated by colon");
            }
        }

        if (begin >= end) {
            throw new IllegalArgumentException("Illegal range for type double: begin must be < end");
        }

        double delta = end - begin;
        if (step == 0.0) {
            step = delta / 100; // default generated step
        }
        int maxSteps = (int) (delta / step);

        intGenerator = new ExpandingRangeIntGenerator(randomProvider.createRandom(), maxSteps / 2, maxSteps / 2, 0, maxSteps);
        this.step = step;
        this.begin = begin;
    }

    public Double generate() {
        return begin + step * intGenerator.nextInt();
    }
}
