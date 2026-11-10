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
import org.openjdk.jcstress.infra.results.IIII_Result;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import java.util.concurrent.atomic.AtomicInteger;

/** The original Java causality test cases; cases 14 and 15 use one iteration. */
public class MansonCausalityTests {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality01 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            if (r1 >= 0) y.setPlain(1);
        }

        @Actor
        public void actor2() {
            r2 = y.getPlain();
            x.setPlain(r2);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality02 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            r2 = x.getPlain();
            if (r1 == r2) y.setPlain(1);
        }

        @Actor
        public void actor2() {
            r3 = y.getPlain();
            x.setPlain(r3);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality03 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            r2 = x.getPlain();
            if (r1 == r2) y.setPlain(1);
        }

        @Actor
        public void actor2() {
            r3 = y.getPlain();
            x.setPlain(r3);
        }

        @Actor
        public void actor3() {
            x.setPlain(2);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality04 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            y.setPlain(r1);
        }

        @Actor
        public void actor2() {
            r2 = y.getPlain();
            x.setPlain(r2);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality05 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            y.setPlain(r1);
        }

        @Actor
        public void actor2() {
            r2 = y.getPlain();
            x.setPlain(r2);
        }

        @Actor
        public void actor3() {
            z.setPlain(1);
        }

        @Actor
        public void actor4() {
            r3 = z.getPlain();
            x.setPlain(r3);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality06 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            if (r1 == 1) y.setPlain(1);
        }

        @Actor
        public void actor2() {
            r2 = y.getPlain();
            if (r2 == 1) x.setPlain(1);
            if (r2 == 0) x.setPlain(1);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality07 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r1 = z.getPlain();
            r2 = x.getPlain();
            y.setPlain(r2);
        }

        @Actor
        public void actor2() {
            r3 = y.getPlain();
            z.setPlain(r3);
            x.setPlain(1);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality08 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            r2 = 1 + r1 * r1 - r1;
            y.setPlain(r2);
        }

        @Actor
        public void actor2() {
            r3 = y.getPlain();
            x.setPlain(r3);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality09 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            r2 = 1 + r1 * r1 - r1;
            y.setPlain(r2);
        }

        @Actor
        public void actor2() {
            r3 = y.getPlain();
            x.setPlain(r3);
        }

        @Actor
        public void actor3() {
            x.setPlain(2);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality10 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            if (r1 == 1) y.setPlain(1);
        }

        @Actor
        public void actor2() {
            r2 = y.getPlain();
            if (r2 == 1) x.setPlain(1);
        }

        @Actor
        public void actor3() {
            z.setPlain(1);
        }

        @Actor
        public void actor4() {
            r3 = z.getPlain();
            if (r3 == 1) x.setPlain(1);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality11 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private final AtomicInteger w = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;
        private int r4 = 0;

        @Actor
        public void actor1() {
            r1 = z.getPlain();
            w.setPlain(r1);
            r2 = x.getPlain();
            y.setPlain(r2);
        }

        @Actor
        public void actor2() {
            r4 = w.getPlain();
            r3 = y.getPlain();
            z.setPlain(r3);
            x.setPlain(1);
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
    public static class TestCausality12 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final int[] a = new int[] { 1, 2 };
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            a[r1] = 0;
            r2 = a[0];
            y.setPlain(r2);
        }

        @Actor
        public void actor2() {
            r3 = y.getPlain();
            x.setPlain(r3);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality13 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            if (r1 == 1) y.setPlain(1);
        }

        @Actor
        public void actor2() {
            r2 = y.getPlain();
            if (r2 == 1) x.setPlain(1);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality14SingleIteration {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger b = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r1 = a.getPlain();
            if (r1 == 0) { y.set(1); } else { b.setPlain(1); }
        }

        @Actor
        public void actor2() {
            r2 = y.get();
            r3 = b.getPlain();
            if (r2 + r3 != 0) a.setPlain(1);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality15SingleIteration {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger b = new AtomicInteger(0);
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r0 = x.get();
            r1 = (r0 == 1 ? a.getPlain() : 0);
            if (r1 == 0) { y.set(1); } else { b.setPlain(1); }
        }

        @Actor
        public void actor2() {
            r2 = y.get();
            r3 = b.getPlain();
            if (r2 + r3 != 0) a.setPlain(1);
        }

        @Actor
        public void actor3() {
            x.set(1);
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
            jcstressResult.r3 = r2;
            jcstressResult.r4 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality16 {
        private final AtomicInteger x = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;

        @Actor
        public void actor1() {
            r1 = x.getPlain();
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            r2 = x.getPlain();
            x.setPlain(2);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality17 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r3 = x.getPlain();
            if (r3 != 42) x.setPlain(42);
            r1 = x.getPlain();
            y.setPlain(r1);
        }

        @Actor
        public void actor2() {
            r2 = y.getPlain();
            x.setPlain(r2);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality18 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            r3 = x.getPlain();
            if (r3 == 0) x.setPlain(42);
            r1 = x.getPlain();
            y.setPlain(r1);
        }

        @Actor
        public void actor2() {
            r2 = y.getPlain();
            x.setPlain(r2);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality19 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            try {
                Thread t3 = new Thread(() -> {
                    r3 = x.getPlain();
                    if (r3 != 42) x.setPlain(42);
                });
                t3.start();
                t3.join();
                r1 = x.getPlain();
                y.setPlain(r1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        @Actor
        public void actor2() {
            r2 = y.getPlain();
            x.setPlain(r2);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCausality20 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            try {
                Thread t3 = new Thread(() -> {
                    r3 = x.getPlain();
                    if (r3 == 0) x.setPlain(42);
                });
                t3.start();
                t3.join();
                r1 = x.getPlain();
                y.setPlain(r1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        @Actor
        public void actor2() {
            r2 = y.getPlain();
            x.setPlain(r2);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r1;
            jcstressResult.r2 = r2;
            jcstressResult.r3 = r3;
        }
    }
}
