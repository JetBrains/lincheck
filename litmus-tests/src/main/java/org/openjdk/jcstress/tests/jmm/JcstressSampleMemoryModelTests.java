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
import java.lang.invoke.VarHandle;

/** Selected OpenJDK JCStress examples. */
public class JcstressSampleMemoryModelTests {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test_jcstress_advanced_02_multi_copy_atomic_fenced {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t2_r1 = 0;
        private int t2_r2 = 0;
        private int t3_r3 = 0;
        private int t3_r4 = 0;

        @Actor
        public void actor1() {
            VarHandle.fullFence();
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            VarHandle.fullFence();
            y.setPlain(1);
        }

        @Actor
        public void actor3() {
            t2_r1 = x.getPlain();
            VarHandle.acquireFence();
            t2_r2 = y.getPlain();
            VarHandle.acquireFence();
        }

        @Actor
        public void actor4() {
            t3_r3 = y.getPlain();
            VarHandle.acquireFence();
            t3_r4 = x.getPlain();
            VarHandle.acquireFence();
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
    public static class Test_jcstress_advanced_02_multi_copy_atomic_fully_fenced {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t2_r1 = 0;
        private int t2_r2 = 0;
        private int t3_r3 = 0;
        private int t3_r4 = 0;

        @Actor
        public void actor1() {
            VarHandle.fullFence();
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            VarHandle.fullFence();
            y.setPlain(1);
        }

        @Actor
        public void actor3() {
            VarHandle.fullFence();
            t2_r1 = x.getPlain();
            VarHandle.fullFence();
            t2_r2 = y.getPlain();
            VarHandle.acquireFence();
        }

        @Actor
        public void actor4() {
            VarHandle.fullFence();
            t3_r3 = y.getPlain();
            VarHandle.fullFence();
            t3_r4 = x.getPlain();
            VarHandle.acquireFence();
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
    public static class Test_jcstress_advanced_02_multi_copy_atomic_opaque {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t2_r1 = 0;
        private int t2_r2 = 0;
        private int t3_r3 = 0;
        private int t3_r4 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
        }

        @Actor
        public void actor3() {
            t2_r1 = x.getOpaque();
            t2_r2 = y.getOpaque();
        }

        @Actor
        public void actor4() {
            t3_r3 = y.getOpaque();
            t3_r4 = x.getOpaque();
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
    public static class Test_jcstress_advanced_03_non_mca_coherence {
        private final AtomicInteger x = new AtomicInteger(0);
        private int t2_r1 = 0;
        private int t2_r2 = 0;
        private int t3_r3 = 0;
        private int t3_r4 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
        }

        @Actor
        public void actor2() {
            x.setOpaque(2);
        }

        @Actor
        public void actor3() {
            t2_r1 = x.getOpaque();
            t2_r2 = x.getOpaque();
        }

        @Actor
        public void actor4() {
            t3_r3 = x.getOpaque();
            t3_r4 = x.getOpaque();
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
    public static class Test_jcstress_advanced_15_volatiles_are_not_fences {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger b = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;
        private int t1_r3 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            b.set(1);
            y.setPlain(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = y.getPlain();
            t1_r2 = b.get();
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
    public static class Test_jcstress_basics_05_coherence_same_read_opaque {
        private final AtomicInteger a = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            a.setOpaque(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = a.getOpaque();
            t1_r2 = a.getOpaque();
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
    public static class Test_jcstress_basics_05_coherence_same_read_plain {
        private final AtomicInteger a = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            a.setPlain(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = a.getPlain();
            t1_r2 = a.getPlain();
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
    public static class Test_jcstress_basics_05_coherence_same_read_volatile {
        private final AtomicInteger a = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            a.set(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = a.get();
            t1_r2 = a.get();
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
    public static class Test_jcstress_basics_06_causality_message_passing_acqrel {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
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
    public static class Test_jcstress_basics_06_causality_message_passing_opaque {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            y.setOpaque(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = y.getOpaque();
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
    public static class Test_jcstress_basics_06_causality_message_passing_plain {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            y.setPlain(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = y.getPlain();
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
    public static class Test_jcstress_basics_06_causality_message_passing_volatile {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.set(1);
            y.set(1);
        }

        @Actor
        public void actor2() {
            t1_r1 = y.get();
            t1_r2 = x.get();
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
    public static class Test_jcstress_basics_07_consensus_dekker_acqrel {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setRelease(1);
            t0_r1 = y.getAcquire();
        }

        @Actor
        public void actor2() {
            y.setRelease(1);
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
    public static class Test_jcstress_basics_07_consensus_dekker_plain {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            t0_r1 = y.getPlain();
        }

        @Actor
        public void actor2() {
            y.setPlain(1);
            t1_r2 = x.getPlain();
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
    public static class Test_jcstress_basics_07_consensus_dekker_volatile {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.set(1);
            t0_r1 = y.get();
        }

        @Actor
        public void actor2() {
            y.set(1);
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
    public static class Test_jcstress_rmw_09_gas_effects_1_cts_cts {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            var t = y.get();
            if (t == 1) {
                y.set(0);
            }
            t0_r1 = t;
        }

        @Actor
        public void actor2() {
            var t = y.get();
            if (t == 0) {
                y.set(1);
            }
            t1_r2 = x.getPlain();
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
    public static class Test_jcstress_rmw_09_gas_effects_2_cas_cas {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            t0_r1 = (y.compareAndSet(1, 0) ? 1 : 0);
        }

        @Actor
        public void actor2() {
            var r3 = y.compareAndSet(0, 1);
            t1_r2 = x.getPlain();
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
    public static class Test_jcstress_rmw_09_gas_effects_3_gts_cas {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            var t = y.get();
            y.set(0);
            t0_r1 = t;
        }

        @Actor
        public void actor2() {
            var r3 = y.compareAndSet(0, 1);
            t1_r2 = x.getPlain();
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
    public static class Test_jcstress_rmw_09_gas_effects_4_gas_cas {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0_r1 = 0;
        private int t1_r2 = 0;

        @Actor
        public void actor1() {
            x.setPlain(1);
            t0_r1 = y.getAndSet(0);
        }

        @Actor
        public void actor2() {
            var r3 = y.compareAndSet(0, 1);
            t1_r2 = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = t0_r1;
            jcstressResult.r2 = t1_r2;
        }
    }
}
