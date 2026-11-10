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
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Litmus tests from the JMT collection. */
public class JmtMemoryModelTests {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestFigure5CompilationCounterexample {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int a = 0;
        private int b = 0;
        private int c = 0;
        private int d = 0;
        private int e = 0;

        @Actor
        public void actor1() {
            x.setRelease(2);
            a = x.getAcquire();
            b = z.get();
            c = y.get();
        }

        @Actor
        public void actor2() {
            y.set(2);
            x.set(1);
        }

        @Actor
        public void actor3() {
            d = x.getOpaque();
            e = x.getOpaque();
        }

        @Arbiter
        public void collect(IIIII_Result jcstressResult) {
            jcstressResult.r1 = a;
            jcstressResult.r2 = b;
            jcstressResult.r3 = c;
            jcstressResult.r4 = d;
            jcstressResult.r5 = e;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_2plus2W_opq {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(2);
            y.setOpaque(1);
            t0_r1 = x.getOpaque();
        }

        @Actor
        public void actor2() {
            y.setOpaque(2);
            x.setOpaque(1);
            t1_r2 = y.getOpaque();
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
    public static class Test_lb_if {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.getPlain();
            if (t0_r1 != 0) {
                y.setPlain(1);
            }
        }

        @Actor
        public void actor2() {
            t1_r2 = y.getPlain();
            if (t1_r2 != 0) {
                x.setPlain(1);
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
    public static class Test_lb_ra {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            t0_r1 = y.getAcquire();
            x.setRelease(1);
        }

        @Actor
        public void actor2() {
            t1_r2 = x.getAcquire();
            y.setRelease(1);
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
    public static class Test_mp_2_access {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int t1_a = 0;
        private int t1_b = 0;
        private int t2_c = 0;
        private int t2_d = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            y.setRelease(1);
            z.setPlain(1);
        }

        @Actor
        public void actor2() {
            t1_a = y.getAcquire();
            t1_b = x.getPlain();
        }

        @Actor
        public void actor3() {
            t2_c = z.getAcquire();
            t2_d = x.getPlain();
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = t1_a;
            jcstressResult.r2 = t1_b;
            jcstressResult.r3 = t2_c;
            jcstressResult.r4 = t2_d;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_mp_fadd_vol {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t2_r2 = 0;
        private int t2_r3 = 0;

        @Actor
        public void actor1() {
            x.setPlain(42);
            y.setRelease(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = y.getAndAdd(1);
        }

        @Actor
        public void actor3() {
            t2_r2 = y.getAcquire();
            t2_r3 = x.getPlain();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t1_r1;
            jcstressResult.r2 = t2_r2;
            jcstressResult.r3 = t2_r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_mp_fake_fence {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger v = new AtomicInteger(0);
        private int t1_r2 = 0;
        private int t1_r4 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            var r1 = v.get();
            v.set(r1);
            y.setPlain(1);
        }

        @Actor
        public void actor2() {
            t1_r2 = y.getPlain();
            var r3 = v.get();
            v.set(r3);
            t1_r4 = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t1_r2;
            jcstressResult.r2 = t1_r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_mp_fence {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger f = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;
        private int t1_r3 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            y.setPlain(1);
            VarHandle.releaseFence();
            f.setPlain(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = f.getPlain();
            t1_r2 = x.getPlain();
            VarHandle.acquireFence();
            t1_r3 = y.getPlain();
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
    public static class Test_mp_large_transfer {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger f = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;
        private int t1_r3 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            y.setPlain(1);
            f.setRelease(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = f.getAcquire();
            t1_r2 = y.getPlain();
            t1_r3 = x.getPlain();
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
    public static class Test_mp_once_two_unsync_writers {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t2_r3 = 0;
        private int t2_r4 = 0;

        @Actor
        public void actor1() {
            t0_r1 = y.get();
            x.setPlain(1);
            y.set(1);
        }

        @Actor
        public void actor2() {
            var r2 = y.get();
            x.setPlain(2);
            y.set(1);
        }

        @Actor
        public void actor3() {
            t2_r3 = y.get();
            t2_r4 = x.getPlain();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t2_r3;
            jcstressResult.r3 = t2_r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_mp_overwrite {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
            y.setRelease(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = y.getAcquire();
            t1_r2 = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t1_r1;
            jcstressResult.r2 = t1_r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_mp_quasi_fence {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger v = new AtomicInteger(0);
        private int t1_r2 = 0;
        private int t1_r4 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            v.set(0);
            var r1 = v.get();
            y.setPlain(1);
        }

        @Actor
        public void actor2() {
            t1_r2 = y.getPlain();
            v.set(0);
            var r3 = v.get();
            t1_r4 = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t1_r2;
            jcstressResult.r2 = t1_r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_mp_trans {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t2_r2 = 0;
        private int t2_r3 = 0;

        @Actor
        public void actor1() {
            x.setRelease(1);
            y.setRelease(2);
        }

        @Actor
        public void actor2() {
            t1_r1 = y.getAcquire();
            x.setRelease(2);
        }

        @Actor
        public void actor3() {
            t2_r2 = x.getAcquire();
            t2_r3 = x.getAcquire();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t1_r1;
            jcstressResult.r2 = t2_r2;
            jcstressResult.r3 = t2_r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_no_future_read_opq {
        private final AtomicInteger x = new AtomicInteger(0);
        private int t0_r1 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.getOpaque();
            x.setOpaque(1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_no_future_read_pln {
        private final AtomicInteger x = new AtomicInteger(0);
        private int t0_r1 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.getPlain();
            x.setPlain(1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_no_future_read_ra {
        private final AtomicInteger x = new AtomicInteger(0);
        private int t0_r1 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.getAcquire();
            x.setRelease(1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_no_future_read_vol {
        private final AtomicInteger x = new AtomicInteger(0);
        private int t0_r1 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.get();
            x.set(1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_release_sequence_opq {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            y.setRelease(1);
            y.setOpaque(2);
        }

        @Actor
        public void actor2() {
            t1_r1 = y.getAcquire();
            t1_r2 = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t1_r1;
            jcstressResult.r2 = t1_r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_release_sequence_pln {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            y.setRelease(1);
            y.setPlain(2);
        }

        @Actor
        public void actor2() {
            t1_r1 = y.getAcquire();
            t1_r2 = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t1_r1;
            jcstressResult.r2 = t1_r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_release_sequence_rmw {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;
        private int t1_r3 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            y.setRelease(1);
            t0_r1 = y.compareAndExchangeRelease(1, 2);
        }

        @Actor
        public void actor2() {
            t1_r2 = y.getAcquire();
            t1_r3 = x.getPlain();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t1_r2;
            jcstressResult.r3 = t1_r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_sb_fadd_volatile_acquire {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            var x0 = x.getAndAdd(1);
            t0_r1 = y.getAcquire();
        }

        @Actor
        public void actor2() {
            var y0 = y.getAndAdd(1);
            t1_r2 = x.getAcquire();
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
    public static class Test_sb_fake_fence {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger v = new AtomicInteger(0);
        private int t0_r2 = 0;
        private int t1_r4 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            var r1 = v.get();
            v.set(r1);
            t0_r2 = y.getPlain();
        }

        @Actor
        public void actor2() {
            y.setPlain(1);
            var r3 = v.get();
            v.set(r3);
            t1_r4 = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t0_r2;
            jcstressResult.r2 = t1_r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_SB_opq_vol {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            t0_r1 = y.get();
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
            t1_r2 = x.get();
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
    public static class Test_sb_quasi_fence {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger v = new AtomicInteger(0);
        private int t0_r2 = 0;
        private int t1_r4 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            v.set(0);
            var r1 = v.get();
            t0_r2 = y.getPlain();
        }

        @Actor
        public void actor2() {
            y.setPlain(1);
            v.set(0);
            var r3 = v.get();
            t1_r4 = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t0_r2;
            jcstressResult.r2 = t1_r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_iriw_sensibly_fenced {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t2_r1 = 0;
        private int t2_r2 = 0;
        private int t3_r3 = 0;
        private int t3_r4 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            y.setPlain(1);
        }

        @Actor
        public void actor3() {
            t2_r1 = x.getPlain();
            VarHandle.fullFence();
            t2_r2 = y.getPlain();
        }

        @Actor
        public void actor4() {
            t3_r3 = y.getPlain();
            VarHandle.fullFence();
            t3_r4 = x.getPlain();
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
    public static class Test_lb_fake_fence {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger v = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r3 = 0;

        @Actor
        public void actor1() {
            t0_r1 = x.getPlain();
            var r2 = v.get();
            v.set(t0_r1);
            y.setPlain(1);
        }

        @Actor
        public void actor2() {
            t1_r3 = y.getPlain();
            var r4 = v.get();
            v.set(r4);
            x.setPlain(1);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t1_r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_mp_2_fence {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int t1_a = 0;
        private int t1_b = 0;
        private int t2_c = 0;
        private int t2_d = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            VarHandle.releaseFence();
            y.setPlain(1);
            z.setPlain(1);
        }

        @Actor
        public void actor2() {
            t1_a = y.getAcquire();
            t1_b = x.getPlain();
        }

        @Actor
        public void actor3() {
            t2_c = z.getAcquire();
            t2_d = x.getPlain();
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = t1_a;
            jcstressResult.r2 = t1_b;
            jcstressResult.r3 = t2_c;
            jcstressResult.r4 = t2_d;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_mp_fadd_acq {
        private final VarHandle handle = MethodHandles.arrayElementVarHandle(int[].class);
        private final AtomicInteger x = new AtomicInteger(0);
        private final int[] y = new int[1];
        private int t1_r1 = 0;
        private int t2_r2 = 0;
        private int t2_r3 = 0;

        @Actor
        public void actor1() {
            x.setPlain(42);
            handle.setRelease(y, 0, 1);
        }

        @Actor
        public void actor2() {
            t1_r1 = (int) handle.getAndAddAcquire(y, 0, 1);
        }

        @Actor
        public void actor3() {
            t2_r2 = (int) handle.getAcquire(y, 0);
            t2_r3 = x.getPlain();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t1_r1;
            jcstressResult.r2 = t2_r2;
            jcstressResult.r3 = t2_r3;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_mp_fadd_rel {
        private final VarHandle handle = MethodHandles.arrayElementVarHandle(int[].class);
        private final AtomicInteger x = new AtomicInteger(0);
        private final int[] y = new int[1];
        private int t1_r1 = 0;
        private int t2_r2 = 0;
        private int t2_r3 = 0;

        @Actor
        public void actor1() {
            x.setPlain(42);
            handle.setRelease(y, 0, 1);
        }

        @Actor
        public void actor2() {
            t1_r1 = (int) handle.getAndAddRelease(y, 0, 1);
        }

        @Actor
        public void actor3() {
            t2_r2 = (int) handle.getAcquire(y, 0);
            t2_r3 = x.getPlain();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t1_r1;
            jcstressResult.r2 = t2_r2;
            jcstressResult.r3 = t2_r3;
        }
    }
}
