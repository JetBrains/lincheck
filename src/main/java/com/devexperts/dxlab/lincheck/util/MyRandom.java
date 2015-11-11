/*
 *  Lincheck - Linearizability checker
 *  Copyright (C) 2015 Devexperts LLC
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.devexperts.dxlab.lincheck.util;

import java.util.Random;

public class MyRandom {
    public static final Random r = new Random(0);

    public static int fromInterval(Interval iv) {
        return r.nextInt(iv.to - iv.from) + iv.from;
    }

    public static int nextInt(int n) {
        return r.nextInt(n);
    }

    public static int nextInt() {
        return r.nextInt();
    }

    public static long nextLong() {
        return r.nextLong();
    }

    public static void busyWait(int nanos) {
        if (nanos == 0) return;

        for (long start = System.nanoTime(); start + nanos >= System.nanoTime(); ){}
    }
}
