package com.devexperts.dxlab.lincheck.test.verifier.quantitative;

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

import java.util.Random;

public class StutteringCounterSimulation {
    private final Random r = new Random(0);
    private final int relaxationFactor;
    private final float stutteringProbability;
    private int value;
    private int stutteringCount;

    public StutteringCounterSimulation(int relaxationFactor, float stutteringProbability) {
        this.relaxationFactor = relaxationFactor;
        this.stutteringProbability = stutteringProbability;
    }

    synchronized int incAndGet() {
        if (stutteringCount + 1 < relaxationFactor && r.nextFloat() < stutteringProbability) {
            stutteringCount++;
            return value;
        } else {
            stutteringCount = 0;
            return ++value;
        }
    }
}
