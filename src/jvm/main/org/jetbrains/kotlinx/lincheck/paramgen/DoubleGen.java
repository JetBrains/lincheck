/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.paramgen;

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

    @Override
    public void resetRange() {
        intGenerator.restart();
    }
}
