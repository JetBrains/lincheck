/*
 * Adapted from Lincheck litmus tests.
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.openjdk.jcstress.tests.jmm;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;
import java.util.concurrent.atomic.AtomicInteger;

/** Transformation examples from Sevcik and Aspinall. */
public class SevcikAspinallMemoryModelTests {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_SA08_irrelevant_read_introduction_source {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            t0_r1 = z.getPlain();
            if (t0_r1 == 0) {
                var r3 = x.getPlain();
                if (r3 == 1) {
                    y.setPlain(1);
                }
            } else {
                var r5 = 1;
                y.setPlain(t0_r1);
            }
        }

        @Actor
        public void actor2() {
            x.setPlain(1);
            t1_r2 = y.getPlain();
            z.setPlain(t1_r2);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t1_r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_SA08_redundant_read_after_read_elimination_source {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            var r1 = x.getPlain();
            y.setPlain(r1);
        }

        @Actor
        public void actor2() {
            t1_r2 = y.getPlain();
            if (t1_r2 == 1) {
                var r3 = y.getPlain();
                x.setPlain(r3);
            } else {
                x.setPlain(1);
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = t1_r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_Sevcik_2008_Tester_source {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int t1_ans = 0;

        @Actor
        public void actor1() {
            var r1 = x.getPlain();
            y.setPlain(r1);
            var ans = r1;
        }

        @Actor
        public void actor2() {
            var r1 = z.get();
            var r2 = y.getPlain();
            if (r2 == 1) {
                var r3 = y.getPlain();
                x.setPlain(r3);
            } else {
                x.setPlain(1);
            }
            t1_ans = r2;
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = t1_ans;
        }
    }
}
