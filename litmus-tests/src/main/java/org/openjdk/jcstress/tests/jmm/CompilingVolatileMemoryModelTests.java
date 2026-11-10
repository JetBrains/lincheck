/*
 * Adapted from Lincheck litmus tests.
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package org.openjdk.jcstress.tests.jmm;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.IIIII_Result;
import org.openjdk.jcstress.infra.results.IIII_Result;
import java.util.concurrent.atomic.AtomicInteger;

/** Examples from Compiling Volatile. */
public class CompilingVolatileMemoryModelTests {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestFigure8BeforeRegisterPromotion {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;
        private int r4 = 0;

        @Actor
        public void actor1() {
            r1 = x.getOpaque();
            r2 = x.getOpaque();
        }

        @Actor
        public void actor2() {
            r3 = y.getOpaque();
            r4 = y.getOpaque();
        }

        @Actor
        public void actor3() {
            x.setOpaque(2);
            z.set(1);
            y.set(1);
        }

        @Actor
        public void actor4() {
            y.set(2);
            x.set(1);
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
            jcstressResult.r4 = r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestFigure9AfterRegisterPromotion {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;
        private int r4 = 0;

        @Actor
        public void actor1() {
            r1 = x.getOpaque();
            r2 = x.getOpaque();
        }

        @Actor
        public void actor2() {
            r3 = y.getOpaque();
            r4 = y.getOpaque();
        }

        @Actor
        public void actor3() {
            x.setOpaque(2);
            y.set(1);
        }

        @Actor
        public void actor4() {
            y.set(2);
            x.set(1);
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
            jcstressResult.r4 = r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_LBP21_volatile_non_sc_4 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t2_r2 = 0;
        private int t3_r3 = 0;
        private int t3_r4 = 0;

        @Actor
        public void actor1() {
            x.set(2);
            t0_r1 = y.get();
        }

        @Actor
        public void actor2() {
            y.set(1);
        }

        @Actor
        public void actor3() {
            t2_r2 = y.get();
            x.set(1);
        }

        @Actor
        public void actor4() {
            t3_r3 = x.get();
            t3_r4 = x.get();
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t2_r2;
            jcstressResult.r3 = t3_r3;
            jcstressResult.r4 = t3_r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_LBP21_volatile_non_sc_5 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t2_r2 = 0;
        private int t3_r3 = 0;
        private int t4_r4 = 0;
        private int t4_r5 = 0;

        @Actor
        public void actor1() {
            x.set(1);
            t0_r1 = y.get();
        }

        @Actor
        public void actor2() {
            y.set(1);
        }

        @Actor
        public void actor3() {
            t2_r2 = y.get();
            z.set(1);
        }

        @Actor
        public void actor4() {
            z.set(2);
            t3_r3 = x.get();
        }

        @Actor
        public void actor5() {
            t4_r4 = z.get();
            t4_r5 = z.get();
        }

        @Arbiter
        public void collect(IIIII_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t2_r2;
            jcstressResult.r3 = t3_r3;
            jcstressResult.r4 = t4_r4;
            jcstressResult.r5 = t4_r5;
        }
    }
}
