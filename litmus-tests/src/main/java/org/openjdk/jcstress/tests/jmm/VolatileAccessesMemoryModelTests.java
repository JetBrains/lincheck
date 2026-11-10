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
import org.openjdk.jcstress.infra.results.IIIII_Result;
import org.openjdk.jcstress.infra.results.IIII_Result;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.lang.invoke.VarHandle;

/** Volatile ordering and fences. */
public class VolatileAccessesMemoryModelTests {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestStoreBufferingWithOneFullFenceAndVolatileAccesses {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            VarHandle.fullFence();
            r0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.set(1);
            r1 = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestFullFenceRetainsWritesSubsumedByReads {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;
        private int r2 = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            // This read subsumes the write in the lower eco frontier.
            r0 = x.getOpaque();
            VarHandle.fullFence();
            r1 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.set(1);
            r2 = x.get();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
            jcstressResult.r3 = r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestFullFencesWithReadsFromTheSameWrite {
        private final AtomicInteger x = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;
        private int r2 = -1;
        private int r3 = -1;

        @Actor
        public void actor1() {
            r0 = x.getOpaque();
            VarHandle.fullFence();
            r1 = x.getOpaque();
        }

        @Actor
        public void actor2() {
            r2 = x.getOpaque();
            VarHandle.fullFence();
            r3 = x.getOpaque();
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
    public static class TestFullFencesWithPlainReadsFromTheSameWrite {
        private final AtomicInteger x = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;
        private int r2 = -1;
        private int r3 = -1;

        @Actor
        public void actor1() {
            r0 = x.getPlain();
            VarHandle.fullFence();
            r1 = x.getPlain();
        }

        @Actor
        public void actor2() {
            r2 = x.getPlain();
            VarHandle.fullFence();
            r3 = x.getPlain();
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
    public static class TestFullFenceOrdersPlainReads {
        private final AtomicInteger x = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            r0 = x.getPlain();
            VarHandle.fullFence();
            r1 = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestFullFenceDoesNotTreatReadSourcesAsPrecedingWrites {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;
        private int r2 = -1;

        @Actor
        public void actor1() {
            r0 = x.getOpaque();
            VarHandle.fullFence();
            r1 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.set(1);
            r2 = x.get();
        }

        @Actor
        public void actor3() {
            x.setOpaque(1);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
            jcstressResult.r3 = r2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestIRIWWithFullFences {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;
        private int r2 = -1;
        private int r3 = -1;

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
            r0 = x.getOpaque();
            VarHandle.fullFence();
            r1 = y.getOpaque();
        }

        @Actor
        public void actor4() {
            r2 = y.getOpaque();
            VarHandle.fullFence();
            r3 = x.getOpaque();
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
    public static class TestIRIWWithPlainReadsAndFullFences {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;
        private int r2 = -1;
        private int r3 = -1;

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
            r0 = x.getPlain();
            VarHandle.fullFence();
            r1 = y.getPlain();
        }

        @Actor
        public void actor4() {
            r2 = y.getPlain();
            VarHandle.fullFence();
            r3 = x.getPlain();
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
    public static class TestIRIWWithPlainAndOpaqueReadsAfterFullFence {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;
        private int r2 = -1;
        private int r3 = -1;
        private int r4 = -1;

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
            r0 = x.getOpaque();
            VarHandle.fullFence();
            r1 = y.getPlain();
            // This read must remain a witness even when the plain read has the same source.
            r2 = y.getOpaque();
        }

        @Actor
        public void actor4() {
            r3 = y.getPlain();
            VarHandle.fullFence();
            r4 = x.getOpaque();
        }

        @Arbiter
        public void collect(IIIII_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
            jcstressResult.r3 = r2;
            jcstressResult.r4 = r3;
            jcstressResult.r5 = r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestIRIWWithPlainAndOpaqueReadsBeforeFullFence {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;
        private int r2 = -1;
        private int r3 = -1;
        private int r4 = -1;

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
            r0 = x.getOpaque();
            VarHandle.fullFence();
            r1 = y.getPlain();
        }

        @Actor
        public void actor4() {
            r2 = y.getPlain();
            // This read must remain a witness even when the plain read has the same source.
            r3 = y.getOpaque();
            VarHandle.fullFence();
            r4 = x.getOpaque();
        }

        @Arbiter
        public void collect(IIIII_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
            jcstressResult.r3 = r2;
            jcstressResult.r4 = r3;
            jcstressResult.r5 = r4;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestFullFencesWithConcurrentPlainAndAtomicWrites {
        private final AtomicInteger x = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            VarHandle.fullFence();
            r0 = x.getOpaque();
        }

        @Actor
        public void actor2() {
            x.setPlain(2);
            VarHandle.fullFence();
            r1 = x.getOpaque();
        }

        @Actor
        public void actor3() {
            x.setOpaque(3);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestRepeatedFullFencesWithOpaqueWrites {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;

        @Actor
        public void actor1() {
            for (int iteration = 0; iteration < 8; iteration++) {
                if (false) { x.setPlain(1); } else { x.setOpaque(1); }
                VarHandle.fullFence();
            }
            r0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            for (int iteration = 0; iteration < 8; iteration++) {
                if (false) { y.setPlain(1); } else { y.setOpaque(1); }
                VarHandle.fullFence();
            }
            r1 = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestRepeatedFullFencesWithPlainWrites {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;

        @Actor
        public void actor1() {
            for (int iteration = 0; iteration < 8; iteration++) {
                if (true) { x.setPlain(1); } else { x.setOpaque(1); }
                VarHandle.fullFence();
            }
            r0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            for (int iteration = 0; iteration < 8; iteration++) {
                if (true) { y.setPlain(1); } else { y.setOpaque(1); }
                VarHandle.fullFence();
            }
            r1 = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestFullFenceWithIndependentVolatileArrayElements {
        private final AtomicIntegerArray values = new AtomicIntegerArray(2);
        private int result = -1;

        @Actor
        public void actor1() {
            values.set(0, 1);
            VarHandle.fullFence();
            result = values.get(1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }
}
