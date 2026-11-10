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
import org.openjdk.jcstress.infra.results.IIIIIII_Result;
import org.openjdk.jcstress.infra.results.IIIIII_Result;
import org.openjdk.jcstress.infra.results.IIIII_Result;
import org.openjdk.jcstress.infra.results.IIII_Result;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.invoke.VarHandle;

/** Litmus tests from the JAM memory model paper. */
public class JamMemoryModelTests {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestSBReleaseOpaque {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            x.setRelease(1);
            r0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
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
    public static class TestSBOpaque {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            r0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
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
    public static class Test4SB {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private final AtomicInteger a = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            r0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
            r1 = z.getOpaque();
        }

        @Actor
        public void actor3() {
            z.setOpaque(1);
            r2 = a.getOpaque();
        }

        @Actor
        public void actor4() {
            a.setOpaque(1);
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
    public static class Test6SB {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger b = new AtomicInteger(0);
        private final AtomicInteger c = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;
        private int r4 = 0;
        private int r5 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            r0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
            r1 = z.getOpaque();
        }

        @Actor
        public void actor3() {
            z.setOpaque(1);
            r2 = a.getOpaque();
        }

        @Actor
        public void actor4() {
            a.setOpaque(1);
            r3 = b.getOpaque();
        }

        @Actor
        public void actor5() {
            b.setOpaque(1);
            r4 = c.getOpaque();
        }

        @Actor
        public void actor6() {
            c.setOpaque(1);
            r5 = x.getOpaque();
        }

        @Arbiter
        public void collect(IIIIII_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = r1;
            jcstressResult.r3 = r2;
            jcstressResult.r4 = r3;
            jcstressResult.r5 = r4;
            jcstressResult.r6 = r5;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestArfna {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger b = new AtomicInteger(0);
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            r0 = x.getOpaque();
            if (r0 != 0) {
                var t = a.getPlain();
                b.setPlain(1);
                if (t != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            r1 = y.getOpaque();
            if (r1 != 0) {
                if (b.getPlain() != 0) {
                    a.setPlain(1);
                    x.setOpaque(1);
                }
            }
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
    public static class TestArfnaTransformed {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger b = new AtomicInteger(0);
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            r0 = x.getOpaque();
            if (r0 != 0) {
                b.setPlain(1);
                var t = a.getPlain();
                if (t != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            r1 = y.getOpaque();
            if (r1 != 0) {
                if (b.getPlain() != 0) {
                    a.setPlain(1);
                    x.setOpaque(1);
                }
            }
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
    public static class TestB {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            r0 = x.getOpaque();
            y.setOpaque(1);
        }

        @Actor
        public void actor2() {
            r1 = y.getOpaque();
            x.setOpaque(1);
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
    public static class TestBReorder {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            y.setOpaque(1);
            r0 = x.getOpaque();
        }

        @Actor
        public void actor2() {
            r1 = y.getOpaque();
            x.setOpaque(1);
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
    public static class TestC {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger p = new AtomicInteger(0);
        private final AtomicInteger q = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            r0 = x.getOpaque();
            if (r0 != 0) {
                var t = p.getPlain();
                q.setPlain(1);
                if (t != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            r1 = y.getOpaque();
            if (r1 != 0) {
                var r2 = q.getPlain();
                if (r2 != 0) {
                    p.setPlain(1);
                    x.setOpaque(1);
                }
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = p.get();
            jcstressResult.r2 = q.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCReorder {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger p = new AtomicInteger(0);
        private final AtomicInteger q = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            r0 = x.getOpaque();
            if (r0 != 0) {
                q.setPlain(1);
                var t = p.getPlain();
                if (t != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            r1 = y.getOpaque();
            if (r1 != 0) {
                var r2 = q.getPlain();
                if (r2 != 0) {
                    p.setPlain(1);
                    x.setOpaque(1);
                }
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = p.get();
            jcstressResult.r2 = q.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCyc {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            r0 = x.getOpaque();
            if (r0 != 0) y.setOpaque(1);
        }

        @Actor
        public void actor2() {
            r1 = y.getOpaque();
            if (r1 != 0) x.setOpaque(1);
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
    public static class TestFig1 {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);

        @Actor
        public void actor1() {
            a.setPlain(1);
            x.getOpaque();
            a.getPlain();
            y.setOpaque(1);
        }

        @Actor
        public void actor2() {
            y.getOpaque();
            x.setOpaque(1);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = a.get();
            jcstressResult.r2 = x.get();
            jcstressResult.r3 = y.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestIriwInternal {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0a = 0;
        private int t0b = 0;
        private int t1a = 0;
        private int t1b = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            t0a = x.getOpaque();
            t0b = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
            t1a = y.getOpaque();
            t1b = x.getOpaque();
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = t0a;
            jcstressResult.r2 = t0b;
            jcstressResult.r3 = t1a;
            jcstressResult.r4 = t1b;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestMpRelaxed {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            y.setOpaque(1);
        }

        @Actor
        public void actor2() {
            r0 = y.getOpaque();
            if (r0 != 0) r1 = x.getPlain();
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
    public static class TestPodrw001 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;
        private int r2 = 0;

        @Actor
        public void actor1() {
            z.setOpaque(1);
            r0 = x.getOpaque();
        }

        @Actor
        public void actor2() {
            x.setOpaque(1);
            r1 = y.getOpaque();
        }

        @Actor
        public void actor3() {
            y.setOpaque(1);
            r2 = z.getOpaque();
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
    public static class TestX001 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int eax = 0;
        private int ebx = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            r0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
            eax = y.getOpaque();
            ebx = x.getOpaque();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = eax;
            jcstressResult.r3 = ebx;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestX003 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int eax = 0;
        private int ebx = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            y.setOpaque(1);
        }

        @Actor
        public void actor2() {
            y.setOpaque(2);
            eax = y.getOpaque();
            ebx = x.getOpaque();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = y.get();
            jcstressResult.r2 = eax;
            jcstressResult.r3 = ebx;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestX006 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            y.setOpaque(1);
        }

        @Actor
        public void actor2() {
            y.setOpaque(2);
            r0 = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = y.get();
            jcstressResult.r2 = r0;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestX86_2plus2W {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);

        @Actor
        public void actor1() {
            x.setOpaque(2);
            y.setOpaque(1);
        }

        @Actor
        public void actor2() {
            y.setOpaque(2);
            x.setOpaque(1);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = x.get();
            jcstressResult.r2 = y.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestA1 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;

        @Actor
        public void actor1() {
            y.getOpaque();
            x.setRelease(1);
        }

        @Actor
        public void actor2() {
            r1 = x.getAcquire();
            if (r1 != 0) {
                y.setPlain(1);
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = x.get();
            jcstressResult.r2 = y.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestA1Reorder {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;

        @Actor
        public void actor1() {
            x.setRelease(1);
            y.getOpaque();
        }

        @Actor
        public void actor2() {
            r1 = x.getAcquire();
            if (r1 != 0) {
                y.setPlain(1);
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = x.get();
            jcstressResult.r2 = y.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestA3 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;

        @Actor
        public void actor1() {
            y.setPlain(1);
            x.setRelease(1);
        }

        @Actor
        public void actor2() {
            r1 = x.getAcquire();
            if (r1 != 0) {
                y.getOpaque();
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestA3Reorder {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;

        @Actor
        public void actor1() {
            y.setPlain(1);
            x.setRelease(1);
        }

        @Actor
        public void actor2() {
            y.getOpaque();
            r1 = x.getAcquire();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestIRIWPoaasLL {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;
        private int r4 = 0;

        @Actor
        public void actor1() {
            x.setRelease(1);
        }

        @Actor
        public void actor2() {
            y.setRelease(1);
        }

        @Actor
        public void actor3() {
            r1 = x.getAcquire();
            r2 = y.getAcquire();
        }

        @Actor
        public void actor4() {
            r3 = y.getAcquire();
            r4 = x.getAcquire();
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
    public static class TestIRIWPoapsLL {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r1 = 0;
        private int r2 = 0;
        private int r3 = 0;
        private int r4 = 0;

        @Actor
        public void actor1() {
            x.setRelease(1);
        }

        @Actor
        public void actor2() {
            y.setRelease(1);
        }

        @Actor
        public void actor3() {
            r1 = x.getAcquire();
            r2 = y.getOpaque();
        }

        @Actor
        public void actor4() {
            r3 = y.getAcquire();
            r4 = x.getOpaque();
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
    public static class TestLinearisation {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger w = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int t = 0;
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            t = x.getAcquire() + y.getPlain();
            if (t == 2) {
                w.setRelease(1);
            }
        }

        @Actor
        public void actor2() {
            r0 = w.getOpaque();
            if (r0 != 0) {
                z.setOpaque(1);
            }
        }

        @Actor
        public void actor3() {
            r1 = z.getOpaque();
            if (r1 != 0) {
                y.setPlain(1);
                x.setRelease(1);
            }
        }

        @Arbiter
        public void collect(IIIII_Result jcstressResult) {
            jcstressResult.r1 = t;
            jcstressResult.r2 = w.get();
            jcstressResult.r3 = x.get();
            jcstressResult.r4 = y.get();
            jcstressResult.r5 = z.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestLinearisation2 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger w = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = 0;

        @Actor
        public void actor1() {
            var tt = x.getAcquire();
            r0 = tt + y.getPlain();
            if (r0 == 2) {
                w.setRelease(1);
            }
        }

        @Actor
        public void actor2() {
            r0 = w.getOpaque();
            if (r0 != 0) {
                z.setOpaque(1);
            }
        }

        @Actor
        public void actor3() {
            r1 = z.getOpaque();
            if (r1 != 0) {
                y.setPlain(1);
                x.setRelease(1);
            }
        }

        @Arbiter
        public void collect(IIIII_Result jcstressResult) {
            jcstressResult.r1 = r0;
            jcstressResult.r2 = w.get();
            jcstressResult.r3 = x.get();
            jcstressResult.r4 = y.get();
            jcstressResult.r5 = z.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestMpRelacq {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            y.setRelease(1);
        }

        @Actor
        public void actor2() {
            r0 = y.getAcquire();
            if (r0 == 1) r1 = x.getPlain();
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
    public static class TestMpRelacqRs {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;
        private int r1 = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            y.setRelease(1);
            y.setOpaque(2);
        }

        @Actor
        public void actor2() {
            r0 = y.getAcquire();
            if (r0 == 2) {
                r1 = x.getPlain();
            }
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
    public static class TestRoachmotel {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int r0 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            z.setRelease(1);
            a.setPlain(1);
        }

        @Actor
        public void actor2() {
            r0 = x.getOpaque();
            if (r0 != 0) {
                z.getAcquire();
                r2 = a.getPlain();
                if (r2 != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor3() {
            r3 = y.getOpaque();
            if (r3 != 0)  {
                x.setOpaque(1);
            }
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = a.get();
            jcstressResult.r2 = z.get();
            jcstressResult.r3 = x.get();
            jcstressResult.r4 = y.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestRoachmotel2 {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int r0 = 0;
        private int r2 = 0;
        private int r3 = 0;

        @Actor
        public void actor1() {
            a.setPlain(1);
            z.setRelease(1);
        }

        @Actor
        public void actor2() {
            r0 = x.getOpaque();
            if (r0 != 0) {
                z.getAcquire();
                r2 = a.getPlain();
                if (r2 != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor3() {
            r3 = y.getOpaque();
            if (r3 != 0) {
                x.setOpaque(1);
            }
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = a.get();
            jcstressResult.r2 = z.get();
            jcstressResult.r3 = x.get();
            jcstressResult.r4 = y.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestRseqWeak {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            y.setPlain(1);
            x.setRelease(1);
            x.setOpaque(3);
        }

        @Actor
        public void actor3() {
            r0 = x.getAcquire();
            if (r0 == 3) {
                y.getPlain();
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = x.get();
            jcstressResult.r2 = y.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestRseqWeak2 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = 0;

        @Actor
        public void actor1() {
            y.setPlain(1);
            x.setRelease(1);
            x.setOpaque(3);
        }

        @Actor
        public void actor2() {
            r0 = x.getAcquire();
            if (r0 == 3) {
                y.getPlain();
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = x.get();
            jcstressResult.r2 = y.get();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestTotalco {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t0r = 0;
        private int t1r = 0;
        private int t2r = 0;

        @Actor
        public void actor1() {
            t0r = x.getOpaque();
            x.setOpaque(1);
        }

        @Actor
        public void actor2() {
            t1r = y.getAcquire();
            x.setOpaque(2);
        }

        @Actor
        public void actor3() {
            t2r = x.getAcquire();
            y.setOpaque(1);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = t0r;
            jcstressResult.r2 = t1r;
            jcstressResult.r3 = t2r;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestWWRRWWRRWsilpPoaaWsilpPoaa {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int t1a = 0;
        private int t1b = 0;
        private int t3a = 0;
        private int t3b = 0;

        @Actor
        public void actor1() {
            x.setRelease(1);
            x.setRelease(2);
        }

        @Actor
        public void actor2() {
            t1a = x.getAcquire();
            t1b = y.getAcquire();
        }

        @Actor
        public void actor3() {
            y.setRelease(1);
            y.setRelease(2);
        }

        @Actor
        public void actor4() {
            t3a = y.getAcquire();
            t3b = x.getAcquire();
        }

        @Arbiter
        public void collect(IIIIII_Result jcstressResult) {
            jcstressResult.r1 = x.get();
            jcstressResult.r2 = y.get();
            jcstressResult.r3 = t1a;
            jcstressResult.r4 = t1b;
            jcstressResult.r5 = t3a;
            jcstressResult.r6 = t3b;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestMpFences {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int r0 = -1;
        private int r1 = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            VarHandle.releaseFence();
            y.setOpaque(1);
        }

        @Actor
        public void actor2() {
            r0 = y.getOpaque();
            VarHandle.acquireFence();
            if (r0 == 1) {
                r1 = x.getPlain();
            }
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
    public static class TestFig6 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int captured2 = 0;
        private int[] captured3;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.set(2);
            y.set(1);
        }

        @Actor
        public void actor2() {
            x.setOpaque(3);
            y.set(2);
        }

        @Actor
        public void actor3() {
            y.set(3);
            captured2 = x.get();
        }

        @Actor
        public void actor4() {
            var s1 = x.getOpaque();
            var s2 = x.getOpaque();
            var s3 = x.getOpaque();
            var t1 = y.getOpaque();
            var t2 = y.getOpaque();
            var t3 = y.getOpaque();
            captured3 = new int[] { s1, s2, s3, t1, t2, t3 };
        }

        @Arbiter
        public void collect(IIIIIII_Result jcstressResult) {
            var r = captured2;
            var t3 = captured3;
            jcstressResult.r1 = r;
            jcstressResult.r2 = t3[0];
            jcstressResult.r3 = t3[1];
            jcstressResult.r4 = t3[2];
            jcstressResult.r5 = t3[3];
            jcstressResult.r6 = t3[4];
            jcstressResult.r7 = t3[5];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestFig6Translated {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int captured2 = 0;
        private int[] captured3;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            x.set(2);
            y.set(1);
        }

        @Actor
        public void actor2() {
            x.set(3);
            y.set(2);
        }

        @Actor
        public void actor3() {
            y.set(3);
            captured2 = x.get();
        }

        @Actor
        public void actor4() {
            var s1 = x.getOpaque();
            var s2 = x.getOpaque();
            var s3 = x.getOpaque();
            var t1 = y.getOpaque();
            var t2 = y.getOpaque();
            var t3 = y.getOpaque();
            captured3 = new int[] { s1, s2, s3, t1, t2, t3 };
        }

        @Arbiter
        public void collect(IIIIIII_Result jcstressResult) {
            var r = captured2;
            var t3 = captured3;
            jcstressResult.r1 = r;
            jcstressResult.r2 = t3[0];
            jcstressResult.r3 = t3[1];
            jcstressResult.r4 = t3[2];
            jcstressResult.r5 = t3[3];
            jcstressResult.r6 = t3[4];
            jcstressResult.r7 = t3[5];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestIriwAcqSc {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int[] captured2;
        private int[] captured3;

        @Actor
        public void actor1() {
            x.set(1);
        }

        @Actor
        public void actor2() {
            y.set(1);
        }

        @Actor
        public void actor3() {
            var r1 = x.getAcquire();
            var r2 = y.get();
            captured2 = new int[] { r1, r2 };
        }

        @Actor
        public void actor4() {
            var r3 = y.getAcquire();
            var r4 = x.get();
            captured3 = new int[] { r3, r4 };
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            var t2 = captured2;
            var t3 = captured3;
            jcstressResult.r1 = t2[0];
            jcstressResult.r2 = t2[1];
            jcstressResult.r3 = t3[0];
            jcstressResult.r4 = t3[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestIriwScRlxAcq {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int[] captured2;
        private int[] captured3;

        @Actor
        public void actor1() {
            x.set(1);
            x.setOpaque(2);
        }

        @Actor
        public void actor2() {
            y.set(1);
            y.setOpaque(2);
        }

        @Actor
        public void actor3() {
            var r1 = x.getAcquire();
            var r2 = y.getAcquire();
            captured2 = new int[] { r1, r2 };
        }

        @Actor
        public void actor4() {
            var r3 = y.getAcquire();
            var r4 = x.getAcquire();
            captured3 = new int[] { r3, r4 };
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            var t2 = captured2;
            var t3 = captured3;
            jcstressResult.r1 = t2[0];
            jcstressResult.r2 = t2[1];
            jcstressResult.r3 = t3[0];
            jcstressResult.r4 = t3[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestIriwSc {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int[] captured2;
        private int[] captured3;

        @Actor
        public void actor1() {
            x.set(1);
        }

        @Actor
        public void actor2() {
            y.set(1);
        }

        @Actor
        public void actor3() {
            var r1 = x.get();
            var r2 = y.get();
            captured2 = new int[] { r1, r2 };
        }

        @Actor
        public void actor4() {
            var r3 = y.get();
            var r4 = x.get();
            captured3 = new int[] { r3, r4 };
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            var t2 = captured2;
            var t3 = captured3;
            jcstressResult.r1 = t2[0];
            jcstressResult.r2 = t2[1];
            jcstressResult.r3 = t3[0];
            jcstressResult.r4 = t3[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestMpSc {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int[] captured1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            y.set(1);
        }

        @Actor
        public void actor2() {
            var r0 = y.get();
            var r1 = -1;
            if (r0 == 1) {
                r1 = x.getPlain();
            }
            captured1 = new int[] { r0, r1 };
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = captured1[0];
            jcstressResult.r2 = captured1[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestZ6U {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int[] captured1;
        private int captured2 = 0;

        @Actor
        public void actor1() {
            x.set(1);
            y.setRelease(1);
        }

        @Actor
        public void actor2() {
            var r1 = y.getAndAdd(1);
            var r2 = y.getOpaque();
            captured1 = new int[] { r1, r2 };
        }

        @Actor
        public void actor3() {
            y.set(3);
            captured2 = x.get();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            var t1 = captured1;
            var t2 = captured2;
            jcstressResult.r1 = t1[0];
            jcstressResult.r2 = t1[1];
            jcstressResult.r3 = t2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestA3v2 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int captured1 = 0;

        @Actor
        public void actor1() {
            y.setPlain(1);
            x.setRelease(1);
        }

        @Actor
        public void actor2() {
            var r0 = x.compareAndExchangeAcquire(1, 2);
            var r1 = -1;
            if (r0 == 1) {
                r1 = y.getPlain();
            }
            captured1 = r1;
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = captured1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCp {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger p = new AtomicInteger(0);
        private final AtomicInteger q = new AtomicInteger(0);

        @Actor
        public void actor1() {
            var r0 = x.getOpaque();
            if (r0 != 0) {
                var t = p.compareAndExchangeAcquire(1, 2);
                q.setPlain(1);
                if (t == 1) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            var r1 = y.getOpaque();
            if (r1 != 0) {
                var r2 = q.getPlain();
                if (r2 != 0) {
                    p.setPlain(1);
                    x.setOpaque(1);
                }
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            var post0 = new int[] { p.get(), q.get() };
            jcstressResult.r1 = post0[0];
            jcstressResult.r2 = post0[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCpReorder {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger p = new AtomicInteger(0);
        private final AtomicInteger q = new AtomicInteger(0);

        @Actor
        public void actor1() {
            var r0 = x.getOpaque();
            if (r0 != 0) {
                q.setPlain(1);
                var t = p.compareAndExchangeAcquire(1, 2);
                if (t == 1) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            var r1 = y.getOpaque();
            if (r1 != 0) {
                var r2 = q.getPlain();
                if (r2 != 0) {
                    p.setPlain(1);
                    x.setOpaque(1);
                }
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            var post0 = new int[] { p.get(), q.get() };
            jcstressResult.r1 = post0[0];
            jcstressResult.r2 = post0[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCpq {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger p = new AtomicInteger(0);
        private final AtomicInteger q = new AtomicInteger(0);

        @Actor
        public void actor1() {
            var r0 = x.getOpaque();
            if (r0 != 0) {
                var t = p.compareAndExchangeAcquire(1, 2);
                var u = q.compareAndExchangeAcquire(0, 1);
                if (t == 1) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            var r1 = y.getOpaque();
            if (r1 != 0) {
                var r2 = q.getPlain();
                if (r2 != 0) {
                    p.setPlain(1);
                    x.setOpaque(1);
                }
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            var post0 = new int[] { p.get(), q.get() };
            jcstressResult.r1 = post0[0];
            jcstressResult.r2 = post0[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCpqReorder {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger p = new AtomicInteger(0);
        private final AtomicInteger q = new AtomicInteger(0);

        @Actor
        public void actor1() {
            var r0 = x.getOpaque();
            if (r0 != 0) {
                var u = q.compareAndExchangeAcquire(0, 1);
                var t = p.compareAndExchangeAcquire(1, 2);
                if (t == 1) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            var r1 = y.getOpaque();
            if (r1 != 0) {
                var r2 = q.getPlain();
                if (r2 != 0) {
                    p.setPlain(1);
                    x.setOpaque(1);
                }
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            var post0 = new int[] { p.get(), q.get() };
            jcstressResult.r1 = post0[0];
            jcstressResult.r2 = post0[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCq {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger p = new AtomicInteger(0);
        private final AtomicInteger q = new AtomicInteger(0);

        @Actor
        public void actor1() {
            var r0 = x.getOpaque();
            if (r0 != 0) {
                var t = p.getPlain();
                var u = q.compareAndExchangeAcquire(0, 1);
                if (t != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            var r1 = y.getOpaque();
            if (r1 != 0) {
                var r2 = q.getPlain();
                if (r2 != 0) {
                    p.setPlain(1);
                    x.setOpaque(1);
                }
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            var post0 = new int[] { p.get(), q.get() };
            jcstressResult.r1 = post0[0];
            jcstressResult.r2 = post0[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCqReorder {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger p = new AtomicInteger(0);
        private final AtomicInteger q = new AtomicInteger(0);

        @Actor
        public void actor1() {
            var r0 = x.getOpaque();
            if (r0 != 0) {
                var u = q.compareAndExchangeAcquire(0, 1);
                var t = p.getPlain();
                if (t != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            var r1 = y.getOpaque();
            if (r1 != 0) {
                var r2 = q.getPlain();
                if (r2 != 0) {
                    p.setPlain(1);
                    x.setOpaque(1);
                }
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            var post0 = new int[] { p.get(), q.get() };
            jcstressResult.r1 = post0[0];
            jcstressResult.r2 = post0[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestX002 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int captured0 = 0;
        private int[] captured1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            VarHandle.fullFence();
            captured0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
            var eax = y.getOpaque();
            var ebx = x.getOpaque();
            captured1 = new int[] { eax, ebx };
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            var eax0 = captured0;
            var t1 = captured1;
            jcstressResult.r1 = eax0;
            jcstressResult.r2 = t1[0];
            jcstressResult.r3 = t1[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestX005 {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int captured0 = 0;
        private int captured1 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            VarHandle.fullFence();
            captured0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
            captured1 = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            var eax0 = captured0;
            var eax1 = captured1;
            jcstressResult.r1 = eax0;
            jcstressResult.r2 = eax1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestSbPlusRfis {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int[] captured0;
        private int[] captured1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            var r0 = x.get();
            var r1 = y.get();
            captured0 = new int[] { r0, r1 };
        }

        @Actor
        public void actor2() {
            y.setPlain(1);
            var r0 = y.get();
            var r1 = x.get();
            captured1 = new int[] { r0, r1 };
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            var t0 = captured0;
            var t1 = captured1;
            jcstressResult.r1 = t0[0];
            jcstressResult.r2 = t0[1];
            jcstressResult.r3 = t1[0];
            jcstressResult.r4 = t1[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestSeq {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);

        @Actor
        public void actor1() {
            a.set(1);
        }

        @Actor
        public void actor2() {
            var r0 = x.getOpaque();
            if (r0 != 0) {
                var r1 = a.get();
                if (r1 != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor3() {
            var r2 = y.getOpaque();
            if (r2 != 0) {
                x.setOpaque(1);
            }
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            var post0 = new int[] { a.get(), x.get(), y.get() };
            jcstressResult.r1 = post0[0];
            jcstressResult.r2 = post0[1];
            jcstressResult.r3 = post0[2];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestSeq2 {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);

        @Actor
        public void actor1() {
            a.set(1);
            var r0 = x.getOpaque();
            if (r0 != 0) {
                var r1 = a.get();
                if (r1 != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor2() {
            var r2 = y.getOpaque();
            if (r2 != 0) {
                x.setOpaque(1);
            }
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            var post0 = new int[] { a.get(), x.get(), y.get() };
            jcstressResult.r1 = post0[0];
            jcstressResult.r2 = post0[1];
            jcstressResult.r3 = post0[2];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestStrengthen {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);

        @Actor
        public void actor1() {
            a.set(1);
            z.setOpaque(1);
        }

        @Actor
        public void actor2() {
            var r0 = x.getOpaque();
            if (r0 != 0) {
                z.getAcquire();
                var r2 = a.get();
                if (r2 != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor3() {
            var r3 = y.getOpaque();
            if (r3 != 0) {
                x.setOpaque(1);
            }
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            var post0 = new int[] { a.get(), z.get(), x.get(), y.get() };
            jcstressResult.r1 = post0[0];
            jcstressResult.r2 = post0[1];
            jcstressResult.r3 = post0[2];
            jcstressResult.r4 = post0[3];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestStrengthen2 {
        private final AtomicInteger a = new AtomicInteger(0);
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);

        @Actor
        public void actor1() {
            a.set(1);
            z.setRelease(1);
        }

        @Actor
        public void actor2() {
            var r0 = x.getOpaque();
            if (r0 != 0) {
                z.getAcquire();
                var r2 = a.get();
                if (r2 != 0) {
                    y.setOpaque(1);
                }
            }
        }

        @Actor
        public void actor3() {
            var r3 = y.getOpaque();
            if (r3 != 0) {
                x.setOpaque(1);
            }
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            var post0 = new int[] { a.get(), z.get(), x.get(), y.get() };
            jcstressResult.r1 = post0[0];
            jcstressResult.r2 = post0[1];
            jcstressResult.r3 = post0[2];
            jcstressResult.r4 = post0[3];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCppMemIriwRelacq {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int[] captured2;
        private int[] captured3;

        @Actor
        public void actor1() {
            x.set(1);
        }

        @Actor
        public void actor2() {
            y.set(1);
        }

        @Actor
        public void actor3() {
            var r1 = x.getAcquire();
            var r2 = y.getAcquire();
            captured2 = new int[] { r1, r2 };
        }

        @Actor
        public void actor4() {
            var r3 = y.getAcquire();
            var r4 = x.getAcquire();
            captured3 = new int[] { r3, r4 };
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            var t2 = captured2;
            var t3 = captured3;
            jcstressResult.r1 = t2[0];
            jcstressResult.r2 = t2[1];
            jcstressResult.r3 = t3[0];
            jcstressResult.r4 = t3[1];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class Test2Plus2W {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int captured0 = 0;
        private int captured1 = 0;

        @Actor
        public void actor1() {
            x.set(1);
            y.set(2);
            captured0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.set(1);
            x.set(2);
            captured1 = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            var r1_0 = captured0;
            var r1_1 = captured1;
            jcstressResult.r1 = r1_0;
            jcstressResult.r2 = r1_1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestPPOCA {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int[] captured1;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            VarHandle.fullFence();
            y.setOpaque(1);
        }

        @Actor
        public void actor2() {
            var x0 = y.getOpaque();
            var x4 = 0;
            var x6 = 0;
            if (x0 == 1) {
                z.setOpaque(1);
                x4 = z.getOpaque();
                x6 = x.getOpaque();
            }
            captured1 = new int[] { x0, x4, x6 };
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = captured1[0];
            jcstressResult.r2 = captured1[1];
            jcstressResult.r3 = captured1[2];
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestSBMfence {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int captured0 = 0;
        private int captured1 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            VarHandle.fullFence();
            captured0 = y.getOpaque();
        }

        @Actor
        public void actor2() {
            y.setOpaque(1);
            VarHandle.fullFence();
            captured1 = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            var eax0 = captured0;
            var eax1 = captured1;
            jcstressResult.r1 = eax0;
            jcstressResult.r2 = eax1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestWRWC {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private final AtomicInteger z = new AtomicInteger(0);
        private int[] captured1;
        private int captured2 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
            z.setRelease(1);
        }

        @Actor
        public void actor2() {
            var r1 = z.getAcquire();
            VarHandle.fullFence();
            var r2 = y.getOpaque();
            captured1 = new int[] { r1, r2 };
        }

        @Actor
        public void actor3() {
            y.setOpaque(1);
            VarHandle.fullFence();
            captured2 = x.getOpaque();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            var t1 = captured1;
            var t2 = captured2;
            jcstressResult.r1 = t1[0];
            jcstressResult.r2 = t1[1];
            jcstressResult.r3 = t2;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestRWCSyncs {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int[] captured1;
        private int captured2 = 0;

        @Actor
        public void actor1() {
            x.setOpaque(1);
        }

        @Actor
        public void actor2() {
            var r1 = x.getOpaque();
            VarHandle.fullFence();
            var r2 = y.getOpaque();
            captured1 = new int[] { r1, r2 };
        }

        @Actor
        public void actor3() {
            y.setOpaque(1);
            VarHandle.fullFence();
            captured2 = x.getOpaque();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            var t1 = captured1;
            var r1 = captured2;
            jcstressResult.r1 = t1[0];
            jcstressResult.r2 = t1[1];
            jcstressResult.r3 = r1;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoRWR {
        private final AtomicInteger x = new AtomicInteger(0);
        private int eax = -1;
        private int ebx = -1;

        @Actor
        public void actor1() {
            eax = x.getOpaque();
            x.setOpaque(1);
            ebx = x.getOpaque();
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = eax;
            jcstressResult.r2 = ebx;
        }
    }
}
