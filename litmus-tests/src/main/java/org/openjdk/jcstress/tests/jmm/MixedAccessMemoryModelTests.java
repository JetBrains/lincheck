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
import org.openjdk.jcstress.infra.results.IIII_Result;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Release chains and volatile fences mixed with plain and opaque accesses. */
public class MixedAccessMemoryModelTests {

    /** Two release writes with a plain relay read and two opaque observations. */
    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class ReleaseChain {
        private final HandleCell cell = new HandleCell();
        private int first = -1;
        private int second = -1;
        private int third = -1;

        @Actor
        public void actor1() {
            HandleCell.X.set(cell, 1);
            HandleCell.Y.setRelease(cell, 1);
        }

        @Actor
        public void actor2() {
            first = (int) HandleCell.Y.get(cell);
            HandleCell.X.setRelease(cell, 2);
        }

        @Actor
        public void actor3() {
            second = (int) HandleCell.X.getOpaque(cell);
            third = (int) HandleCell.X.getOpaque(cell);
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
            jcstressResult.r3 = third;
        }
    }

    /** Volatile write/read pairs between plain accesses, using VarHandle. */
    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class VolatileFenceVarHandle {
        private final HandleCell cell = new HandleCell();
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            HandleCell.X.set(cell, 1);
            HandleCell.FLAG.setVolatile(cell, 42);
            HandleCell.FLAG.getVolatile(cell);
            first = (int) HandleCell.Y.get(cell);
        }

        @Actor
        public void actor2() {
            HandleCell.Y.set(cell, 1);
            HandleCell.FLAG.setVolatile(cell, 42);
            HandleCell.FLAG.getVolatile(cell);
            second = (int) HandleCell.X.get(cell);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    /** Same volatile write/read pairs using a declared volatile field. */
    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class VolatileFence {
        private final VolatileCell cell = new VolatileCell();
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            cell.x = 1;
            cell.flag = 42;
            var unused = cell.flag;
            first = cell.y;
        }

        @Actor
        public void actor2() {
            cell.y = 1;
            cell.flag = 42;
            var unused = cell.flag;
            second = cell.x;
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    /** Uses a declared volatile field and also records the volatile read values. */
    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class VolatileFenceWithReadbacks {
        private final VolatileCell cell = new VolatileCell();
        private int firstFlag = -1;
        private int secondFlag = -1;
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            cell.x = 1;
            cell.flag = 42;
            firstFlag = cell.flag;
            first = cell.y;
        }

        @Actor
        public void actor2() {
            cell.y = 1;
            cell.flag = 42;
            secondFlag = cell.flag;
            second = cell.x;
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = firstFlag;
            jcstressResult.r2 = secondFlag;
            jcstressResult.r3 = first;
            jcstressResult.r4 = second;
        }
    }

    private static class HandleCell {
        int x;
        int y;
        int flag;
        static final VarHandle X = fieldHandle(HandleCell.class, "x");
        static final VarHandle Y = fieldHandle(HandleCell.class, "y");
        static final VarHandle FLAG = fieldHandle(HandleCell.class, "flag");
    }

    private static class VolatileCell {
        int x;
        int y;
        volatile int flag;
    }

    private static VarHandle fieldHandle(Class<?> owner, String name) {
        try {
            return MethodHandles.lookup().findVarHandle(owner, name, int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
