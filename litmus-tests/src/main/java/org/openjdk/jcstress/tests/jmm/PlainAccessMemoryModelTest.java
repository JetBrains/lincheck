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
import org.openjdk.jcstress.infra.results.IIIII_Result;
import org.openjdk.jcstress.infra.results.IIII_Result;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.IIZ_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.IZ_Result;
import org.openjdk.jcstress.infra.results.ZII_Result;
import org.openjdk.jcstress.infra.results.ZZ_Result;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Plain coherence, mixed access modes, read-modify-write operations and common-subexpression elimination. */
public class PlainAccessMemoryModelTest {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestPlainReadsSeparatedByUnrelatedOpaqueRead {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger unrelated = new AtomicInteger(0);
        private int first = -1;
        private int middle = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            middle = unrelated.getOpaque();
            second = x.getPlain();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = middle;
            jcstressResult.r3 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestPlainReadsSeparatedByAcquireFence {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            VarHandle.acquireFence();
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
    public static class TestPlainReadsSeparatedByLoadLoadFence {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            VarHandle.loadLoadFence();
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
    public static class TestAliasedPlainOpaquePlainReads {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger alias = x;
        private int first = -1;
        private int middle = -1;
        private int last = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            middle = alias.getOpaque();
            last = x.getPlain();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = middle;
            jcstressResult.r3 = last;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestAliasedPlainOpaquePlainReadsAfterCommonSubexpressionElimination {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger alias = x;
        private int first = -1;
        private int middle = -1;
        private int last = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            var cached = x.getPlain();
            first = cached;
            middle = alias.getOpaque();
            last = cached;
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = middle;
            jcstressResult.r3 = last;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestAliasedPlainReads {
        private final Cell x = new Cell();
        private final Cell y = x;
        private int first = -1;
        private int second = -1;
        private int third = -1;

        @Actor
        public void actor1() {
            x.value = 1;
            x.value = 2;
        }

        @Actor
        public void actor2() {
            first = x.value;
            second = y.value;
            third = x.value;
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
            jcstressResult.r3 = third;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestAliasedPlainReadsAfterCommonSubexpressionElimination {
        private final Cell x = new Cell();
        private final Cell y = x;
        private int first = -1;
        private int second = -1;
        private int third = -1;

        @Actor
        public void actor1() {
            x.value = 1;
            x.value = 2;
        }

        @Actor
        public void actor2() {
            var cached = x.value;
            first = cached;
            second = y.value;
            third = cached;
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
            jcstressResult.r3 = third;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestOpaqueReadsOfConcurrentPlainWrites {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;
        private int third = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            x.setPlain(2);
        }

        @Actor
        public void actor3() {
            first = x.getOpaque();
            second = x.getOpaque();
            third = x.getOpaque();
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
            jcstressResult.r3 = third;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestPlainReadAfterReleaseAcquirePublication {
        private final Cell x = new Cell();
        private final AtomicInteger published = new AtomicInteger(0);
        private int publication = -1;
        private int value = -1;

        @Actor
        public void actor1() {
            x.value = 1;
            published.setRelease(1);
        }

        @Actor
        public void actor2() {
            publication = published.getAcquire();
            value = x.value;
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = publication;
            jcstressResult.r2 = value;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestLatestPlainWriteAfterCrossThreadPublication {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger published = new AtomicInteger(0);
        private int publication = -1;
        // The happens-before-latest write belongs to the first-created thread.

        @Actor
        public void actor1() {
            publication = published.getAcquire();
            if (publication == 1) x.setPlain(2);
        }

        @Actor
        public void actor2() {
            x.setPlain(1);
            published.setRelease(1);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = publication;
            jcstressResult.r2 = x.getOpaque();
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestOwnPlainWriteRemainsVisibleAfterAnInterveningRead {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            first = x.getPlain();
            second = x.getPlain();
        }

        @Actor
        public void actor2() {
            x.setPlain(2);
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
    public static class TestPlainCompareAndSetRemainsAtomic {
        private final AtomicInteger x = new AtomicInteger(0);
        private boolean first = false;
        private boolean second = false;

        @Actor
        public void actor1() {
            first = x.weakCompareAndSetPlain(0, 1);
        }

        @Actor
        public void actor2() {
            second = x.weakCompareAndSetPlain(0, 2);
        }

        @Arbiter
        public void collect(ZZ_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestPlainCompareAndSetCompetingForAPlainWrite {
        private final AtomicInteger x = new AtomicInteger(0);
        private boolean first = false;
        private boolean second = false;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            first = x.weakCompareAndSetPlain(1, 2);
        }

        @Actor
        public void actor3() {
            second = x.weakCompareAndSetPlain(1, 3);
        }

        @Arbiter
        public void collect(ZZ_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestPlainReadBeforeFailedReleaseCompareAndExchange {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            second = x.compareAndExchangeRelease(2, 3);
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
    public static class TestFailedReleaseCompareAndExchangeBeforePlainRead {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            first = x.compareAndExchangeRelease(2, 3);
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
    public static class TestPlainReadBeforeSuccessfulReleaseCompareAndExchange {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int witness = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            first = x.getPlain();
            witness = x.compareAndExchangeRelease(0, 2);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = witness;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestAcquireCompareAndExchangeWriteHasAtomicCoherence {
        private final AtomicInteger x = new AtomicInteger(0);
        private int witness = -1;
        private int first = -1;
        private int second = -1;
        private int third = -1;
        private int fourth = -1;

        @Actor
        public void actor1() {
            witness = x.compareAndExchangeAcquire(0, 1);
        }

        @Actor
        public void actor2() {
            x.setOpaque(2);
        }

        @Actor
        public void actor3() {
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Actor
        public void actor4() {
            third = x.getOpaque();
            fourth = x.getOpaque();
        }

        @Arbiter
        public void collect(IIIII_Result jcstressResult) {
            jcstressResult.r1 = witness;
            jcstressResult.r2 = first;
            jcstressResult.r3 = second;
            jcstressResult.r4 = third;
            jcstressResult.r5 = fourth;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestAtomicCoherenceOrderExtendsThroughHappensBeforeToPlainWrites {
        private final AtomicInteger x = new AtomicInteger(0);
        private final AtomicInteger y = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            first = x.getPlain();
            x.set(1);
            second = y.get();
        }

        @Actor
        public void actor2() {
            y.set(1);
            x.set(2);
            x.setPlain(3);
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
    public static class TestConsecutivePlainCompareAndSetsCannotConsumeTheSamePlainWrite {
        private final AtomicInteger x = new AtomicInteger(0);
        private boolean first = false;
        private boolean second = false;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            first = x.weakCompareAndSetPlain(1, 2);
            second = x.weakCompareAndSetPlain(1, 3);
        }

        @Arbiter
        public void collect(ZZ_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCoherenceThroughPlainRmwSourceAtomicOrderAndPlainSuffix {
        private final AtomicInteger x = new AtomicInteger(0);
        private boolean success = false;
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setPlain(2);
        }

        @Actor
        public void actor2() {
            success = x.weakCompareAndSetPlain(2, 3);
            if (success) {
                x.setOpaque(4);
                x.setPlain(5);
            }
        }

        @Actor
        public void actor3() {
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Arbiter
        public void collect(ZII_Result jcstressResult) {
            jcstressResult.r1 = success;
            jcstressResult.r2 = first;
            jcstressResult.r3 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestOpaqueReadsAcrossPlainClusters {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setOpaque(2);
            x.setPlain(3);
            x.setOpaque(4);
            x.setPlain(5);
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
    public static class TestPlainClustersKeepAtomicBoundsLocalToEachField {
        private final TwoFieldCell cells = new TwoFieldCell();
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            // Both plain writes initially have the same allocation event as their only atomic bound.
            cells.first = 1;
            cells.second = 1;
            TwoFieldCell.FIRST.setOpaque(cells, 2);
            TwoFieldCell.SECOND.setOpaque(cells, 2);
        }

        @Actor
        public void actor2() {
            first = (int) TwoFieldCell.SECOND.getOpaque(cells);
            second = (int) TwoFieldCell.SECOND.getOpaque(cells);
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
    public static class TestConcurrentPlainWriteCanOverlapAtomicBounds {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
            x.setOpaque(2);
            x.setPlain(3);
        }

        @Actor
        public void actor2() {
            x.setPlain(4);
        }

        @Actor
        public void actor3() {
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
    public static class TestIndependentRmwChainsWithPlainSources {
        private final AtomicInteger x = new AtomicInteger(0);
        private boolean first = false;
        private boolean second = false;

        @Actor
        public void actor1() {
            x.setPlain(1);
            first = x.weakCompareAndSetPlain(1, 2);
        }

        @Actor
        public void actor2() {
            x.setPlain(3);
            second = x.weakCompareAndSetPlain(3, 4);
        }

        @Arbiter
        public void collect(ZZ_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestRmwChainWithPlainSourceAndConcurrentOpaqueWrite {
        private final AtomicInteger x = new AtomicInteger(0);
        private boolean first = false;
        private boolean second = false;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            first = x.weakCompareAndSetPlain(1, 2);
            second = x.weakCompareAndSetPlain(2, 3);
        }

        @Actor
        public void actor3() {
            x.setOpaque(4);
        }

        @Arbiter
        public void collect(ZZ_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestOppositeOpaqueObservationsOfConcurrentPlainWrites {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;
        private int third = -1;
        private int fourth = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            if (false) { x.setOpaque(2); } else { x.setPlain(2); }
        }

        @Actor
        public void actor3() {
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Actor
        public void actor4() {
            third = x.getOpaque();
            fourth = x.getOpaque();
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
            jcstressResult.r3 = third;
            jcstressResult.r4 = fourth;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestOppositeOpaqueObservationsOfConcurrentPlainAndOpaqueWrites {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;
        private int third = -1;
        private int fourth = -1;

        @Actor
        public void actor1() {
            x.setPlain(1);
        }

        @Actor
        public void actor2() {
            if (true) { x.setOpaque(2); } else { x.setPlain(2); }
        }

        @Actor
        public void actor3() {
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Actor
        public void actor4() {
            third = x.getOpaque();
            fourth = x.getOpaque();
        }

        @Arbiter
        public void collect(IIII_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
            jcstressResult.r3 = third;
            jcstressResult.r4 = fourth;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestPlainReadBeforePlainCompareAndSetWithPlainWriter {
        private final AtomicInteger x = new AtomicInteger(0);
        private int value = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (false) { x.setOpaque(1); } else { x.setPlain(1); }
        }

        @Actor
        public void actor2() {
            value = x.getPlain();
            success = x.weakCompareAndSetPlain(0, 2);
        }

        @Arbiter
        public void collect(IZ_Result jcstressResult) {
            jcstressResult.r1 = value;
            jcstressResult.r2 = success;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestPlainReadBeforePlainCompareAndSetWithOpaqueWriter {
        private final AtomicInteger x = new AtomicInteger(0);
        private int value = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (true) { x.setOpaque(1); } else { x.setPlain(1); }
        }

        @Actor
        public void actor2() {
            value = x.getPlain();
            success = x.weakCompareAndSetPlain(0, 2);
        }

        @Arbiter
        public void collect(IZ_Result jcstressResult) {
            jcstressResult.r1 = value;
            jcstressResult.r2 = success;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCasAcrossPlainWritesObservedByOpaqueReads {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (false) { x.setOpaque(1); } else { x.setPlain(1); }
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Actor
        public void actor2() {
            success = x.weakCompareAndSetPlain(1, 3);
        }

        @Actor
        public void actor3() {
            if (false) { x.setOpaque(2); } else { x.setPlain(2); }
        }

        @Arbiter
        public void collect(IIZ_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
            jcstressResult.r3 = success;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCasAcrossPlainSourceAndOpaqueWriteObservedByOpaqueReads {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (false) { x.setOpaque(1); } else { x.setPlain(1); }
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Actor
        public void actor2() {
            success = x.weakCompareAndSetPlain(1, 3);
        }

        @Actor
        public void actor3() {
            if (true) { x.setOpaque(2); } else { x.setPlain(2); }
        }

        @Arbiter
        public void collect(IIZ_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
            jcstressResult.r3 = success;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCasAcrossOpaqueSourceAndPlainWriteObservedByOpaqueReads {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (true) { x.setOpaque(1); } else { x.setPlain(1); }
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Actor
        public void actor2() {
            success = x.weakCompareAndSetPlain(1, 3);
        }

        @Actor
        public void actor3() {
            if (false) { x.setOpaque(2); } else { x.setPlain(2); }
        }

        @Arbiter
        public void collect(IIZ_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
            jcstressResult.r3 = success;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestCasAcrossOpaqueWritesObservedByOpaqueReads {
        private final AtomicInteger x = new AtomicInteger(0);
        private int first = -1;
        private int second = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (true) { x.setOpaque(1); } else { x.setPlain(1); }
            first = x.getOpaque();
            second = x.getOpaque();
        }

        @Actor
        public void actor2() {
            success = x.weakCompareAndSetPlain(1, 3);
        }

        @Actor
        public void actor3() {
            if (true) { x.setOpaque(2); } else { x.setPlain(2); }
        }

        @Arbiter
        public void collect(IIZ_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
            jcstressResult.r3 = success;
        }
    }

    private static class Cell {
        int value;
    }

    private static class TwoFieldCell {
        int first;
        int second;
        static final VarHandle FIRST = fieldHandle(TwoFieldCell.class, "first");
        static final VarHandle SECOND = fieldHandle(TwoFieldCell.class, "second");
    }

    private static VarHandle fieldHandle(Class<?> owner, String name) {
        try {
            return MethodHandles.lookup().findVarHandle(owner, name, int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Bare load buffering on plain fields; the JMM permits (1, 1), which the checker does not explore.
     * Plain fields rather than AtomicInteger.getPlain/setPlain: C2 wraps the Unsafe-based accessors in compiler-only
     * barriers and does not reorder them, so only direct field accesses let the JIT form the cycle.
     */
    @JCStressTest
    @State
    @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Load buffering: each read sees the other actor's later write.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestPlainLoadBuffering {
        private int x;
        private int y;
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            first = x;
            y = 1;
        }

        @Actor
        public void actor2() {
            second = y;
            x = 1;
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = first;
            jcstressResult.r2 = second;
        }
    }
}
