/*
 * Adapted from Lincheck litmus tests.
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openjdk.jcstress.tests.jmm;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.II_Result;
import java.util.concurrent.atomic.AtomicInteger;

/** Coherence matrix for plain, opaque, acquire/release and volatile accesses. */
public class CoRRMemoryModelTest {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_PLAIN_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_PLAIN_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_PLAIN_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_PLAIN_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_OPAQUE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_OPAQUE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_OPAQUE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_OPAQUE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_RELEASE_ACQUIRE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_RELEASE_ACQUIRE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_RELEASE_ACQUIRE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_RELEASE_ACQUIRE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_VOLATILE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_VOLATILE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_VOLATILE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__PLAIN_VOLATILE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_PLAIN_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_PLAIN_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_PLAIN_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_PLAIN_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_OPAQUE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_OPAQUE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_OPAQUE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_OPAQUE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_RELEASE_ACQUIRE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_RELEASE_ACQUIRE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_RELEASE_ACQUIRE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_RELEASE_ACQUIRE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_VOLATILE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_VOLATILE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_VOLATILE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__OPAQUE_VOLATILE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_PLAIN_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_PLAIN_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_PLAIN_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_PLAIN_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_OPAQUE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_OPAQUE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_OPAQUE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_OPAQUE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_RELEASE_ACQUIRE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_RELEASE_ACQUIRE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_RELEASE_ACQUIRE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_RELEASE_ACQUIRE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_VOLATILE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_VOLATILE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_VOLATILE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__RELEASE_ACQUIRE_VOLATILE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_PLAIN_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_PLAIN_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_PLAIN_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_PLAIN_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_OPAQUE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_OPAQUE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_OPAQUE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_OPAQUE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getOpaque();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_RELEASE_ACQUIRE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_RELEASE_ACQUIRE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_RELEASE_ACQUIRE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_RELEASE_ACQUIRE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.getAcquire();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_VOLATILE_PLAIN {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_VOLATILE_OPAQUE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_VOLATILE_RELEASE_ACQUIRE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.getAcquire();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRR__VOLATILE_VOLATILE_VOLATILE {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.set(1);
            x.set(2);
        }

        @Actor
        public void actor2() {
            first = x.get();
            second = x.get();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }
}
