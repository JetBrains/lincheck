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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Plain coherence, mixed access modes, read-modify-write operations and common-subexpression elimination. */
public class PlainAccessMemoryModelTest {

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestPlainReadsSeparatedByUnrelatedOpaqueRead {
        int x;
        int unrelated;
        private static final VarHandle X = fieldHandle(TestPlainReadsSeparatedByUnrelatedOpaqueRead.class, "x");
        private static final VarHandle UNRELATED = fieldHandle(TestPlainReadsSeparatedByUnrelatedOpaqueRead.class, "unrelated");
        private int first = -1;
        private int middle = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            first = x;
            middle = (int) UNRELATED.getOpaque(this);
            second = x;
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
        int x;
        private static final VarHandle X = fieldHandle(TestPlainReadsSeparatedByAcquireFence.class, "x");
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            first = x;
            VarHandle.acquireFence();
            second = x;
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
        int x;
        private static final VarHandle X = fieldHandle(TestAliasedPlainOpaquePlainReads.class, "x");
        private int first = -1;
        private int middle = -1;
        private int last = -1;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            first = x;
            middle = (int) X.getOpaque(this);
            last = x;
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
        int x;
        private static final VarHandle X = fieldHandle(TestAliasedPlainOpaquePlainReadsAfterCommonSubexpressionElimination.class, "x");
        private int first = -1;
        private int middle = -1;
        private int last = -1;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            var cached = x;
            first = cached;
            middle = (int) X.getOpaque(this);
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
        int x;
        private static final VarHandle X = fieldHandle(TestOpaqueReadsOfConcurrentPlainWrites.class, "x");
        private int first = -1;
        private int second = -1;
        private int third = -1;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            x = 2;
        }

        @Actor
        public void actor3() {
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
            third = (int) X.getOpaque(this);
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
        int x;
        int published;
        private static final VarHandle PUBLISHED = fieldHandle(TestPlainReadAfterReleaseAcquirePublication.class, "published");
        private int publication = -1;
        private int value = -1;

        @Actor
        public void actor1() {
            x = 1;
            PUBLISHED.setRelease(this, 1);
        }

        @Actor
        public void actor2() {
            publication = (int) PUBLISHED.getAcquire(this);
            value = x;
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
        int x;
        int published;
        private static final VarHandle X = fieldHandle(TestLatestPlainWriteAfterCrossThreadPublication.class, "x");
        private static final VarHandle PUBLISHED = fieldHandle(TestLatestPlainWriteAfterCrossThreadPublication.class, "published");
        private int publication = -1;
        // The happens-before-latest write belongs to the first-created thread.

        @Actor
        public void actor1() {
            publication = (int) PUBLISHED.getAcquire(this);
            if (publication == 1) x = 2;
        }

        @Actor
        public void actor2() {
            x = 1;
            PUBLISHED.setRelease(this, 1);
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = publication;
            jcstressResult.r2 = (int) X.getOpaque(this);
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class TestOwnPlainWriteRemainsVisibleAfterAnInterveningRead {
        int x;
        private static final VarHandle X = fieldHandle(TestOwnPlainWriteRemainsVisibleAfterAnInterveningRead.class, "x");
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x = 1;
            first = x;
            second = x;
        }

        @Actor
        public void actor2() {
            x = 2;
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
        int x;
        private static final VarHandle X = fieldHandle(TestPlainCompareAndSetRemainsAtomic.class, "x");
        private boolean first = false;
        private boolean second = false;

        @Actor
        public void actor1() {
            first = X.weakCompareAndSetPlain(this, 0, 1);
        }

        @Actor
        public void actor2() {
            second = X.weakCompareAndSetPlain(this, 0, 2);
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
        int x;
        private static final VarHandle X = fieldHandle(TestPlainCompareAndSetCompetingForAPlainWrite.class, "x");
        private boolean first = false;
        private boolean second = false;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            first = X.weakCompareAndSetPlain(this, 1, 2);
        }

        @Actor
        public void actor3() {
            second = X.weakCompareAndSetPlain(this, 1, 3);
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
        int x;
        private static final VarHandle X = fieldHandle(TestPlainReadBeforeFailedReleaseCompareAndExchange.class, "x");
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            first = x;
            second = (int) X.compareAndExchangeRelease(this, 2, 3);
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
        int x;
        private static final VarHandle X = fieldHandle(TestFailedReleaseCompareAndExchangeBeforePlainRead.class, "x");
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            first = (int) X.compareAndExchangeRelease(this, 2, 3);
            second = x;
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
        int x;
        private static final VarHandle X = fieldHandle(TestPlainReadBeforeSuccessfulReleaseCompareAndExchange.class, "x");
        private int first = -1;
        private int witness = -1;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            first = x;
            witness = (int) X.compareAndExchangeRelease(this, 0, 2);
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
        int x;
        private static final VarHandle X = fieldHandle(TestAcquireCompareAndExchangeWriteHasAtomicCoherence.class, "x");
        private int witness = -1;
        private int first = -1;
        private int second = -1;
        private int third = -1;
        private int fourth = -1;

        @Actor
        public void actor1() {
            witness = (int) X.compareAndExchangeAcquire(this, 0, 1);
        }

        @Actor
        public void actor2() {
            X.setOpaque(this, 2);
        }

        @Actor
        public void actor3() {
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
        }

        @Actor
        public void actor4() {
            third = (int) X.getOpaque(this);
            fourth = (int) X.getOpaque(this);
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
        int x;
        int y;
        private static final VarHandle X = fieldHandle(TestAtomicCoherenceOrderExtendsThroughHappensBeforeToPlainWrites.class, "x");
        private static final VarHandle Y = fieldHandle(TestAtomicCoherenceOrderExtendsThroughHappensBeforeToPlainWrites.class, "y");
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            first = x;
            X.setVolatile(this, 1);
            second = (int) Y.getVolatile(this);
        }

        @Actor
        public void actor2() {
            Y.setVolatile(this, 1);
            X.setVolatile(this, 2);
            x = 3;
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
        int x;
        private static final VarHandle X = fieldHandle(TestConsecutivePlainCompareAndSetsCannotConsumeTheSamePlainWrite.class, "x");
        private boolean first = false;
        private boolean second = false;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            first = X.weakCompareAndSetPlain(this, 1, 2);
            second = X.weakCompareAndSetPlain(this, 1, 3);
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
        int x;
        private static final VarHandle X = fieldHandle(TestCoherenceThroughPlainRmwSourceAtomicOrderAndPlainSuffix.class, "x");
        private boolean success = false;
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x = 1;
            x = 2;
        }

        @Actor
        public void actor2() {
            success = X.weakCompareAndSetPlain(this, 2, 3);
            if (success) {
                X.setOpaque(this, 4);
                x = 5;
            }
        }

        @Actor
        public void actor3() {
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
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
        int x;
        private static final VarHandle X = fieldHandle(TestOpaqueReadsAcrossPlainClusters.class, "x");
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x = 1;
            X.setOpaque(this, 2);
            x = 3;
            X.setOpaque(this, 4);
            x = 5;
        }

        @Actor
        public void actor2() {
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
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
        int x;
        private static final VarHandle X = fieldHandle(TestConcurrentPlainWriteCanOverlapAtomicBounds.class, "x");
        private int first = -1;
        private int second = -1;

        @Actor
        public void actor1() {
            x = 1;
            X.setOpaque(this, 2);
            x = 3;
        }

        @Actor
        public void actor2() {
            x = 4;
        }

        @Actor
        public void actor3() {
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
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
        int x;
        private static final VarHandle X = fieldHandle(TestIndependentRmwChainsWithPlainSources.class, "x");
        private boolean first = false;
        private boolean second = false;

        @Actor
        public void actor1() {
            x = 1;
            first = X.weakCompareAndSetPlain(this, 1, 2);
        }

        @Actor
        public void actor2() {
            x = 3;
            second = X.weakCompareAndSetPlain(this, 3, 4);
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
        int x;
        private static final VarHandle X = fieldHandle(TestRmwChainWithPlainSourceAndConcurrentOpaqueWrite.class, "x");
        private boolean first = false;
        private boolean second = false;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            first = X.weakCompareAndSetPlain(this, 1, 2);
            second = X.weakCompareAndSetPlain(this, 2, 3);
        }

        @Actor
        public void actor3() {
            X.setOpaque(this, 4);
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
        int x;
        private static final VarHandle X = fieldHandle(TestOppositeOpaqueObservationsOfConcurrentPlainWrites.class, "x");
        private int first = -1;
        private int second = -1;
        private int third = -1;
        private int fourth = -1;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            if (false) { X.setOpaque(this, 2); } else { x = 2; }
        }

        @Actor
        public void actor3() {
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
        }

        @Actor
        public void actor4() {
            third = (int) X.getOpaque(this);
            fourth = (int) X.getOpaque(this);
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
        int x;
        private static final VarHandle X = fieldHandle(TestOppositeOpaqueObservationsOfConcurrentPlainAndOpaqueWrites.class, "x");
        private int first = -1;
        private int second = -1;
        private int third = -1;
        private int fourth = -1;

        @Actor
        public void actor1() {
            x = 1;
        }

        @Actor
        public void actor2() {
            if (true) { X.setOpaque(this, 2); } else { x = 2; }
        }

        @Actor
        public void actor3() {
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
        }

        @Actor
        public void actor4() {
            third = (int) X.getOpaque(this);
            fourth = (int) X.getOpaque(this);
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
        int x;
        private static final VarHandle X = fieldHandle(TestPlainReadBeforePlainCompareAndSetWithPlainWriter.class, "x");
        private int value = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (false) { X.setOpaque(this, 1); } else { x = 1; }
        }

        @Actor
        public void actor2() {
            value = x;
            success = X.weakCompareAndSetPlain(this, 0, 2);
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
        int x;
        private static final VarHandle X = fieldHandle(TestPlainReadBeforePlainCompareAndSetWithOpaqueWriter.class, "x");
        private int value = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (true) { X.setOpaque(this, 1); } else { x = 1; }
        }

        @Actor
        public void actor2() {
            value = x;
            success = X.weakCompareAndSetPlain(this, 0, 2);
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
        int x;
        private static final VarHandle X = fieldHandle(TestCasAcrossPlainWritesObservedByOpaqueReads.class, "x");
        private int first = -1;
        private int second = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (false) { X.setOpaque(this, 1); } else { x = 1; }
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
        }

        @Actor
        public void actor2() {
            success = X.weakCompareAndSetPlain(this, 1, 3);
        }

        @Actor
        public void actor3() {
            if (false) { X.setOpaque(this, 2); } else { x = 2; }
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
        int x;
        private static final VarHandle X = fieldHandle(TestCasAcrossPlainSourceAndOpaqueWriteObservedByOpaqueReads.class, "x");
        private int first = -1;
        private int second = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (false) { X.setOpaque(this, 1); } else { x = 1; }
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
        }

        @Actor
        public void actor2() {
            success = X.weakCompareAndSetPlain(this, 1, 3);
        }

        @Actor
        public void actor3() {
            if (true) { X.setOpaque(this, 2); } else { x = 2; }
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
        int x;
        private static final VarHandle X = fieldHandle(TestCasAcrossOpaqueSourceAndPlainWriteObservedByOpaqueReads.class, "x");
        private int first = -1;
        private int second = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (true) { X.setOpaque(this, 1); } else { x = 1; }
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
        }

        @Actor
        public void actor2() {
            success = X.weakCompareAndSetPlain(this, 1, 3);
        }

        @Actor
        public void actor3() {
            if (false) { X.setOpaque(this, 2); } else { x = 2; }
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
        int x;
        private static final VarHandle X = fieldHandle(TestCasAcrossOpaqueWritesObservedByOpaqueReads.class, "x");
        private int first = -1;
        private int second = -1;
        private boolean success = false;

        @Actor
        public void actor1() {
            if (true) { X.setOpaque(this, 1); } else { x = 1; }
            first = (int) X.getOpaque(this);
            second = (int) X.getOpaque(this);
        }

        @Actor
        public void actor2() {
            success = X.weakCompareAndSetPlain(this, 1, 3);
        }

        @Actor
        public void actor3() {
            if (true) { X.setOpaque(this, 2); } else { x = 2; }
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
