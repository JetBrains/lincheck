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

package com.devexperts.dxlab.lincheck;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class StatData {
    public static List<Integer> errorIters = new ArrayList<>();
    public static List<Integer> errorConcurIters = new ArrayList<>();
    public static List<Long> errorTimes = new ArrayList<>();


    public static void clear() {
        errorIters.clear();
        errorConcurIters.clear();
        errorTimes.clear();
    }

    public static void addIter(int v) {
        errorIters.add(v);
    }

    public static void addConcur(int v) {
        errorConcurIters.add(v);
    }

    public static void addTime(long v) {
        errorTimes.add(v);
    }

    public static void print(PrintWriter writer) {
        if (writer == null) {
            System.out.println("errorIters = " + errorIters);
            System.out.println("errorConcurIters = " + errorConcurIters);
            System.out.println("errorTimes = " + errorTimes);
        } else {
            writer.println("errorIters = " + errorIters);
            writer.println("errorConcurIters = " + errorConcurIters);
            writer.println("errorTimes = " + errorTimes);
        }
    }
}
