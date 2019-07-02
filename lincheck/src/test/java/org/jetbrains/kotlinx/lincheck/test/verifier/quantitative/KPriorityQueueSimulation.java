package org.jetbrains.kotlinx.lincheck.test.verifier.quantitative;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class KPriorityQueueSimulation {
    private final Random r = new Random(0);
    private final int relaxationFactor;
    private final List<Integer> values = new ArrayList<>();

    private int countTopNotPopped;

    public KPriorityQueueSimulation(int relaxationFactor) {
        this.relaxationFactor = relaxationFactor;
    }

    synchronized void push(int value) {
        // Push is not relaxed
        values.add(value);
        Collections.sort(values);
    }

    synchronized Integer poll() {
        if (values.isEmpty()) {
            countTopNotPopped = 0;
            return null;
        }
        int index = r.nextInt(Math.min(relaxationFactor - countTopNotPopped, values.size()));
        if (index == 0)
            countTopNotPopped = 0;
        else
            countTopNotPopped++;
        return values.remove(index);
    }
}
