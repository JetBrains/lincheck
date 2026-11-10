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
import org.openjdk.jcstress.infra.results.IIII_Result;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import java.util.concurrent.atomic.AtomicInteger;

/** Litmus tests from Manson’s Java memory model thesis. */
public class MansonThesisMemoryModelTests {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_jmanson_thesis_fig_2_2 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r2 = 0;
        private int t1_r1 = 0;

        @Actor
        public void actor1() {
            y.setPlain(1);
            t0_r2 = x.getPlain();
        }

        @Actor
        public void actor2() {
            x.setPlain(2);
            t1_r1 = y.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t0_r2;
            jcstressResult.r2 = t1_r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_jmanson_thesis_fig_2_5 {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger b = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;
        private int t1_r3 = 0;

        @Actor
        public void actor1() {
            a.setPlain(1);
            b.setPlain(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = b.getPlain();
            t1_r2 = a.getPlain();
            t1_r3 = a.getPlain();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t1_r1;
            jcstressResult.r2 = t1_r2;
            jcstressResult.r3 = t1_r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_jmanson_thesis_fig_3_1 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.getPlain();
            if (t0_r1 != 0) {
                y.setPlain(42);
            }
        }

        @Actor
        public void actor2() {
            t1_r2 = y.getPlain();
            if (t1_r2 != 0) {
                x.setPlain(42);
            }
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
    public static class Test_jmanson_thesis_fig_3_3b {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger b = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t0_r2 = 0;
        private int t1_r3 = 0;

        @Actor
        public void actor1() {
            b.setPlain(2);
            t0_r1 = a.getPlain();
            t0_r2 = t0_r1;
        }

        @Actor
        public void actor2() {
            t1_r3 = b.getPlain();
            a.setPlain(t1_r3);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t0_r2;
            jcstressResult.r3 = t1_r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_jmanson_thesis_fig_3_9 {
        private final AtomicInteger v1 = new AtomicInteger(0);
        private final AtomicInteger v2 = new AtomicInteger(0);
        private int t2_r1 = 0;
        private int t2_r2 = 0;
        private int t3_r3 = 0;
        private int t3_r4 = 0;

        @Actor
        public void actor1() {
            v1.set(1);
        }

        @Actor
        public void actor2() {
            v2.set(2);
        }

        @Actor
        public void actor3() {
            t2_r1 = v1.get();
            t2_r2 = v2.get();
        }

        @Actor
        public void actor4() {
            t3_r3 = v2.get();
            t3_r4 = v1.get();
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = t2_r1;
            jcstressResult.r2 = t2_r2;
            jcstressResult.r3 = t3_r3;
            jcstressResult.r4 = t3_r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_jmanson_thesis_fig_4_5 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;
        private int t3_r0 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.getPlain();
            y.setPlain(t0_r1);
        }

        @Actor
        public void actor2() {
            t1_r2 = y.getPlain();
            x.setPlain(t1_r2);
        }

        @Actor
        public void actor3() {
            z.setPlain(42);
        }

        @Actor
        public void actor4() {
            t3_r0 = z.getPlain();
            x.setPlain(t3_r0);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t3_r0;
            jcstressResult.r2 = t0_r1;
            jcstressResult.r3 = t1_r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_jmanson_thesis_fig_4_6 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;
        private int t3_r0 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.getPlain();
            if (t0_r1 == 1) {
                y.setPlain(1);
            }
        }

        @Actor
        public void actor2() {
            t1_r2 = y.getPlain();
            if (t1_r2 == 1) {
                x.setPlain(1);
            }
        }

        @Actor
        public void actor3() {
            z.setPlain(1);
        }

        @Actor
        public void actor4() {
            t3_r0 = z.getPlain();
            if (t3_r0 == 1) {
                x.setPlain(42);
            }
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t3_r0;
            jcstressResult.r2 = t0_r1;
            jcstressResult.r3 = t1_r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_jmanson_thesis_fig_4_8 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger a0 = new AtomicInteger(1);
        private final AtomicInteger a1 = new AtomicInteger(2);
        private int t0_r1 = 0;
        private int t0_r2 = 0;
        private int t1_r3 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.getPlain();
            if (t0_r1 == 0) {
                a0.setPlain(0);
            } else {
                if (t0_r1 == 1) {
                    a1.setPlain(0);
                } else {
                    var aoobe = 1;
                }
            }
            t0_r2 = a0.getPlain();
            y.setPlain(t0_r2);
        }

        @Actor
        public void actor2() {
            t1_r3 = y.getPlain();
            x.setPlain(t1_r3);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t0_r2;
            jcstressResult.r3 = t1_r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_jmanson_thesis_fig_4_9 {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger b = new AtomicInteger(0);
        private final AtomicInteger c = new AtomicInteger(0);
        private final AtomicInteger d = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;
        private int t2_r3 = 0;
        private int t3_r4 = 0;

        @Actor
        public void actor1() {
            t0_r1 = a.getPlain();
            if (t0_r1 == 0) {
                b.setPlain(1);
            }
        }

        @Actor
        public void actor2() {
            t1_r2 = b.getPlain();
            if (t1_r2 == 1) {
                c.setPlain(1);
            }
        }

        @Actor
        public void actor3() {
            t2_r3 = c.getPlain();
            if (t2_r3 == 1) {
                d.setPlain(1);
            }
        }

        @Actor
        public void actor4() {
            t3_r4 = d.getPlain();
            if (t3_r4 == 1) {
                c.setPlain(1);
                a.setPlain(1);
            }
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t1_r2;
            jcstressResult.r3 = t2_r3;
            jcstressResult.r4 = t3_r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_jmanson_thesis_fig_4_11 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;
        private int t2_r3 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.getPlain();
            if (t0_r1 == 0) {
                x.setPlain(1);
            }
        }

        @Actor
        public void actor2() {
            t1_r2 = x.getPlain();
            y.setPlain(t1_r2);
        }

        @Actor
        public void actor3() {
            t2_r3 = y.getPlain();
            x.setPlain(t2_r3);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t1_r2;
            jcstressResult.r3 = t2_r3;
        }
    }
}
