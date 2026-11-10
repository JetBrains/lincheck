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
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Final-field initialization and unsafe publication,
 * including VarHandle reads of final fields in every read access mode.
 */
public class PublicationTests {
    private static VarHandle fieldHandle(Class<?> owner, String name, Class<?> type) {
        try {
            return MethodHandles.lookup().findVarHandle(owner, name, type);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitiveLeakedAfterAssignment {
        private AssignedLeakingFinal published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            new AssignedLeakingFinal(42, it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.field;
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class SuperclassFreezePrecedesSubclassLeak {
        private Derived published = null;
        private int baseResult = -1;
        private int derivedResult = -1;

        @Actor
        public void actor1() {
            new Derived(42, it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed != null) {
                baseResult = observed.baseField;
                derivedResult = observed.derivedField;
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = baseResult;
            jcstressResult.r2 = derivedResult;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class DelegatingConstructorDoesNotRefreezeFinalReference {
        private DelegatingConstructor published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new DelegatingConstructor(42);
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var cell = observed.cell;
                result = cell == null ? -2 : cell.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class HolderPublicationAfterFreezeProtectsEarlierLeak {
        private Holder<LeakingFinal> published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            var holder = new Holder<LeakingFinal>();
            new LeakingFinal(42, it -> { holder.value = it; });
            published = holder;
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var value = observed.value;
                result = value == null ? -2 : value.field;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class PlainRelayCarriesFreezeOfPrematurelyPublishedObject {
        private LeakingFinal published = null;
        private LeakingFinal forwarded = null;
        private final Holder<LeakingFinal> early = new Holder<LeakingFinal>();
        private int result = -1;

        @Actor
        public void actor1() {
            published = new LeakingFinal(42, it -> { early.value = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed != null) forwarded = observed;
        }

        @Actor
        public void actor3() {
            var observed = forwarded;
            result = observed == null ? -1 : observed.field;
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class AcquireProtectsPreviouslyReadLeakedReference {
        private LeakingFinal published = null;
        private final AtomicInteger ready = new AtomicInteger(0);
        private int result = -1;

        @Actor
        public void actor1() {
            new LeakingFinal(42, it -> { published = it; });
            ready.set(1);
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed != null) {
                result = ready.get() == 1 ? observed.field : -2;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceRejectsStaleWritesAndAllowsLaterWrites {
        private FinalCellReference published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            var cell = new Cell(1);
            cell.value = 42;
            published = new FinalCellReference(cell);
            cell.value = 43;
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var cell = observed.cell;
                result = cell == null ? -2 : cell.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceProtectsWritesFromJoinedThread {
        private FinalCellReference published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            try {
                var cell = new Cell(1);
                Thread initializer = new Thread(() -> {
                    cell.value = 42;
                });
                initializer.start();
                initializer.join();
                published = new FinalCellReference(cell);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var cell = observed.cell;
                result = cell == null ? -2 : cell.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferencePreservesConcurrentProtectedWrites {
        private FinalCellReference published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            try {
                var cell = new Cell(1);
                Thread first = new Thread(() -> {
                    cell.value = 42;
                });
                first.start();
                Thread second = new Thread(() -> {
                    cell.value = 43;
                });
                second.start();
                first.join();
                second.join();
                published = new FinalCellReference(cell);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var cell = observed.cell;
                result = cell == null ? -2 : cell.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceRejectsStaleWriteFromAnotherThread {
        private FinalCellReference published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            try {
                var cell = new Cell(1);
                Thread initializer = new Thread(() -> {
                    cell.value = 2;
                });
                initializer.start();
                initializer.join();
                cell.value = 42;
                published = new FinalCellReference(cell);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var cell = observed.cell;
                result = cell == null ? -2 : cell.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceProtectsArrayElements {
        private FinalArray published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalArray(new int[] { 42 });
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var values = observed.values;
                result = values == null ? -2 : values[0];
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceProtectsTransitiveDereference {
        private FinalChain published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalChain(new Link(new Cell(42)));
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var root = observed.root;
                var next = root == null ? null : root.next;
                result = next == null ? -2 : next.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class AbruptConstructorExitFreezesAssignedFinalField {
        private Holder<ThrowingConstructor> published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            var holder = new Holder<ThrowingConstructor>();
            try {
                new ThrowingConstructor(42, it -> { holder.value = it; });
            } catch (ConstructionFailed ignored) {
                published = holder;
            }
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var value = observed.value;
                result = value == null ? -2 : value.field;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class ExceptionFromCalledMethodFreezesAssignedFinalField {
        private Holder<ImplicitlyThrowingConstructor> published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            var holder = new Holder<ImplicitlyThrowingConstructor>();
            try {
                new ImplicitlyThrowingConstructor(42, it -> { holder.value = it; });
            } catch (ConstructionFailed ignored) {
                published = holder;
            }
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var value = observed.value;
                result = value == null ? -2 : value.field;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class HiddenFieldsHaveDistinctWritesAndFreezes {
        private HiddenFields published = null;
        private int baseResult = -1;
        private int derivedResult = -1;
        private int inheritedResult = -1;

        @Actor
        public void actor1() {
            new HiddenFields(41, 42, it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed != null) {
                baseResult = observed.baseValue();
                derivedResult = observed.derivedValue();
                inheritedResult = observed.inheritedValue();
            }
        }

        @Arbiter
        public void collect(III_Result jcstressResult) {
            jcstressResult.r1 = baseResult;
            jcstressResult.r2 = derivedResult;
            jcstressResult.r3 = inheritedResult;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class CompletedSubclassProtectsBothHiddenFields {
        private HiddenFields published = null;
        private int baseResult = -1;
        private int derivedResult = -1;

        @Actor
        public void actor1() {
            published = new HiddenFields(41, 42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed != null) {
                baseResult = observed.baseValue();
                derivedResult = observed.derivedValue();
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = baseResult;
            jcstressResult.r2 = derivedResult;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class RepeatedReferenceReadRetainsEarlierFreezeView {
        private LeakingFinal early = null;
        private LeakingFinal published = null;
        private final AtomicReference<LeakingFinal> handoff = new AtomicReference<LeakingFinal>(null);
        private int result = -1;

        @Actor
        public void actor1() {
            published = new LeakingFinal(42, it -> { early = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed != null) handoff.set(observed);
        }

        @Actor
        public void actor3() {
            if (handoff.get() != null) {
                var observed = early;
                result = observed == null ? -2 : observed.field;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class PlainRepeatedReferenceReadRetainsEarlierFreezeView {
        private LeakingFinal early = null;
        private LeakingFinal published = null;
        private int sawPublished = -1;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new LeakingFinal(42, it -> { early = it; });
        }

        @Actor
        public void actor2() {
            var laterReference = published;
            var earlierReference = early;
            sawPublished = laterReference == null ? 0 : 1;
            result = earlierReference == null ? -1 : earlierReference.field;
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = sawPublished;
            jcstressResult.r2 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class RepeatedReferenceWriteCarriesAccumulatedView {
        private LeakingFinal early = null;
        private LeakingFinal published = null;
        private LeakingFinal forwarded = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new LeakingFinal(42, it -> { early = it; });
        }

        @Actor
        public void actor2() {
            if (published != null) forwarded = early;
        }

        @Actor
        public void actor3() {
            var observed = forwarded;
            result = observed == null ? -1 : observed.field;
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class RepeatedAliasRetainsFinalReferenceProtection {
        private Cell bare = null;
        private FinalCellReference published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            var cell = new Cell(42);
            bare = cell;
            published = new FinalCellReference(cell);
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed != null) {
                if (observed.cell == null) {
                    result = -3;
                } else {
                    var cell = bare;
                    result = cell == null ? -2 : cell.value;
                }
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class UnrelatedReferenceDoesNotAccumulateFreezeView {
        private LeakingFinal early = null;
        private Object unrelated = null;
        private int result = -1;

        @Actor
        public void actor1() {
            new LeakingFinal(42, it -> { early = it; });
            unrelated = new Object();
        }

        @Actor
        public void actor2() {
            if (unrelated != null) {
                var observed = early;
                result = observed == null ? -2 : observed.field;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class SameReferenceViewsRemainThreadLocal {
        private LeakingFinal early = null;
        private LeakingFinal published = null;
        private int observed = 0;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new LeakingFinal(42, it -> { early = it; });
        }

        @Actor
        public void actor2() {
            if (published != null) observed = 1;
        }

        @Actor
        public void actor3() {
            if (observed == 1) {
                var reference = early;
                result = reference == null ? -2 : reference.field;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class LaterReferenceViewDoesNotProtectEarlierRead {
        private LeakingFinal early = null;
        private LeakingFinal published = null;
        private int before = -1;
        private int after = -1;

        @Actor
        public void actor1() {
            published = new LeakingFinal(42, it -> { early = it; });
        }

        @Actor
        public void actor2() {
            var first = early;
            before = first == null ? -1 : first.field;
            var second = published;
            after = second == null ? -1 : second.field;
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = before;
            jcstressResult.r2 = after;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitive {
        private FinalPrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalPrimitiveValue(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.field;
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitiveWithLeak {
        private FinalPrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalPrimitiveValue(42, it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.field;
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class MutablePrimitive {
        private MutablePrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new MutablePrimitiveValue(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.field;
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class MutablePrimitiveWithLeak {
        private MutablePrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new MutablePrimitiveValue(42, it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.field;
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReference {
        private FinalReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalReferenceValue(new Object(), it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.field == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceWithLeak {
        private FinalReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalReferenceValue(new Object(), it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.field == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class MutableReference {
        private MutableReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new MutableReferenceValue(new Object(), it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.field == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class MutableReferenceWithLeak {
        private MutableReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new MutableReferenceValue(new Object(), it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.field == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalAndMutableFields {
        private MixedFields published = null;
        private int finalResult = -1;
        private int mutableResult = -1;

        @Actor
        public void actor1() {
            published = new MixedFields(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed != null) {
                finalResult = observed.finalField;
                mutableResult = observed.mutableField;
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = finalResult;
            jcstressResult.r2 = mutableResult;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalAndMutableFieldsWithLeak {
        private MixedFields published = null;
        private int finalResult = -1;
        private int mutableResult = -1;

        @Actor
        public void actor1() {
            published = new MixedFields(42, it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed != null) {
                finalResult = observed.finalField;
                mutableResult = observed.mutableField;
            }
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = finalResult;
            jcstressResult.r2 = mutableResult;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceProtectsMutableField {
        private FinalCellReference published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalCellReference(new Cell(42));
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var cell = observed.cell;
                result = cell == null ? -2 : cell.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class ConstructorReadsDefaultBeforeAssignmentAndOwnWriteAfterward {
        private int[] result = new int[] { -1, -1 };

        @Actor
        public void actor1() {
            var observed = new ConstructorReads(42);
            result = new int[] { observed.before, observed.after };
        }

        @Arbiter
        public void collect(II_Result jcstressResult) {
            jcstressResult.r1 = result[0];
            jcstressResult.r2 = result[1];
        }
    }

    // Constructor callbacks preserve the precise publication point relative to each final-field assignment.
    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitiveGet {
        private FinalPrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalPrimitiveValue(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.get();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitiveGetOpaque {
        private FinalPrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalPrimitiveValue(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.getOpaque();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitiveGetAcquire {
        private FinalPrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalPrimitiveValue(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.getAcquire();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitiveGetVolatile {
        private FinalPrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalPrimitiveValue(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.getVolatile();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitiveGetWithLeak {
        private FinalPrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalPrimitiveValue(42, it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.get();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitiveGetOpaqueWithLeak {
        private FinalPrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalPrimitiveValue(42, it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.getOpaque();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitiveGetAcquireWithLeak {
        private FinalPrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalPrimitiveValue(42, it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.getAcquire();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalPrimitiveGetVolatileWithLeak {
        private FinalPrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalPrimitiveValue(42, it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.getVolatile();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class MutablePrimitiveGet {
        private MutablePrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new MutablePrimitiveValue(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.get();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class MutablePrimitiveGetOpaque {
        private MutablePrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new MutablePrimitiveValue(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.getOpaque();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class MutablePrimitiveGetAcquire {
        private MutablePrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new MutablePrimitiveValue(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.getAcquire();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class MutablePrimitiveGetVolatile {
        private MutablePrimitiveValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new MutablePrimitiveValue(42, it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : observed.getVolatile();
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGet {
        private FinalReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalReferenceValue(new Object(), it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.get() == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetOpaque {
        private FinalReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalReferenceValue(new Object(), it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.getOpaque() == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetAcquire {
        private FinalReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalReferenceValue(new Object(), it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.getAcquire() == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetVolatile {
        private FinalReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalReferenceValue(new Object(), it -> { });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.getVolatile() == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetWithLeak {
        private FinalReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalReferenceValue(new Object(), it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.get() == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetOpaqueWithLeak {
        private FinalReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalReferenceValue(new Object(), it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.getOpaque() == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetAcquireWithLeak {
        private FinalReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalReferenceValue(new Object(), it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.getAcquire() == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetVolatileWithLeak {
        private FinalReferenceValue published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalReferenceValue(new Object(), it -> { published = it; });
        }

        @Actor
        public void actor2() {
            var observed = published;
            result = observed == null ? -1 : (observed.getVolatile() == null ? 0 : 1);
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetProtectsMutableField {
        private FinalCellReference published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalCellReference(new Cell(42));
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var cell = observed.get();
                result = cell == null ? -2 : cell.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetOpaqueProtectsMutableField {
        private FinalCellReference published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalCellReference(new Cell(42));
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var cell = observed.getOpaque();
                result = cell == null ? -2 : cell.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetAcquireProtectsMutableField {
        private FinalCellReference published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalCellReference(new Cell(42));
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var cell = observed.getAcquire();
                result = cell == null ? -2 : cell.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    @JCStressTest
    @State
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class FinalReferenceGetVolatileProtectsMutableField {
        private FinalCellReference published = null;
        private int result = -1;

        @Actor
        public void actor1() {
            published = new FinalCellReference(new Cell(42));
        }

        @Actor
        public void actor2() {
            var observed = published;
            if (observed == null) {
                result = -1;
            } else {
                var cell = observed.getVolatile();
                result = cell == null ? -2 : cell.value;
            }
        }

        @Arbiter
        public void collect(I_Result jcstressResult) {
            jcstressResult.r1 = result;
        }
    }

    private static class FinalPrimitiveValue {
        private static final VarHandle FIELD = fieldHandle(FinalPrimitiveValue.class, "field", int.class);
        final int field;
        FinalPrimitiveValue(int value, Consumer<FinalPrimitiveValue> publish) {
            publish.accept(this);
            field = value;
        }
        int get() { return (int) FIELD.get(this); }
        int getOpaque() { return (int) FIELD.getOpaque(this); }
        int getAcquire() { return (int) FIELD.getAcquire(this); }
        int getVolatile() { return (int) FIELD.getVolatile(this); }
    }

    private static class MutablePrimitiveValue {
        private static final VarHandle FIELD = fieldHandle(MutablePrimitiveValue.class, "field", int.class);
        int field;
        MutablePrimitiveValue(int value, Consumer<MutablePrimitiveValue> publish) {
            publish.accept(this);
            field = value;
        }
        int get() { return (int) FIELD.get(this); }
        int getOpaque() { return (int) FIELD.getOpaque(this); }
        int getAcquire() { return (int) FIELD.getAcquire(this); }
        int getVolatile() { return (int) FIELD.getVolatile(this); }
    }

    private static class FinalReferenceValue {
        private static final VarHandle FIELD = fieldHandle(FinalReferenceValue.class, "field", Object.class);
        final Object field;
        FinalReferenceValue(Object value, Consumer<FinalReferenceValue> publish) {
            publish.accept(this);
            field = value;
        }
        Object get() { return FIELD.get(this); }
        Object getOpaque() { return FIELD.getOpaque(this); }
        Object getAcquire() { return FIELD.getAcquire(this); }
        Object getVolatile() { return FIELD.getVolatile(this); }
    }

    private static class MutableReferenceValue {
        Object field;
        MutableReferenceValue(Object value, Consumer<MutableReferenceValue> publish) {
            publish.accept(this);
            field = value;
        }
    }

    private static class MixedFields {
        final int finalField;
        int mutableField;
        MixedFields(int value, Consumer<MixedFields> publish) {
            publish.accept(this);
            finalField = value;
            mutableField = value;
        }
    }

    private static class Base {
        final int baseField;
        Base(int value) { baseField = value; }
    }

    private static class Derived extends Base {
        final int derivedField;
        Derived(int value, Consumer<Derived> publish) {
            super(value);
            publish.accept(this);
            derivedField = value;
        }
    }

    private static class HiddenGrandBase {
        private final int inherited;
        HiddenGrandBase(int value) { inherited = value; }
    }

    private static class HiddenBase extends HiddenGrandBase {
        private final int value;
        protected final int inherited;
        HiddenBase(int value) {
            super(99);
            this.value = value;
            inherited = value;
        }
        int baseValue() { return value; }
    }

    private static class HiddenFields extends HiddenBase {
        private final int value;
        HiddenFields(int baseValue, int derivedValue, Consumer<HiddenFields> publish) {
            super(baseValue);
            publish.accept(this);
            value = derivedValue;
        }
        int derivedValue() { return value; }
        int inheritedValue() { return inherited; }
    }

    private static class DelegatingConstructor {
        final Cell cell;
        private DelegatingConstructor(Cell cell) { this.cell = cell; }
        DelegatingConstructor(int value) {
            this(new Cell(1));
            cell.value = value;
        }
    }

    private static class LeakingFinal {
        final int field;
        LeakingFinal(int value, Consumer<LeakingFinal> publish) {
            publish.accept(this);
            field = value;
        }
    }

    private static class AssignedLeakingFinal {
        final int field;
        AssignedLeakingFinal(int value, Consumer<AssignedLeakingFinal> publish) {
            field = value;
            publish.accept(this);
        }
    }

    private static class Holder<T> { T value; }
    private static class Cell {
        int value;
        Cell(int value) { this.value = value; }
    }
    private static class Link {
        Cell next;
        Link(Cell next) { this.next = next; }
    }
    private static class FinalCellReference {
        private static final VarHandle CELL = fieldHandle(FinalCellReference.class, "cell", Cell.class);
        final Cell cell;
        FinalCellReference(Cell cell) { this.cell = cell; }
        Cell get() { return (Cell) CELL.get(this); }
        Cell getOpaque() { return (Cell) CELL.getOpaque(this); }
        Cell getAcquire() { return (Cell) CELL.getAcquire(this); }
        Cell getVolatile() { return (Cell) CELL.getVolatile(this); }
    }
    private static class FinalArray {
        final int[] values;
        FinalArray(int[] values) { this.values = values; }
    }
    private static class FinalChain {
        final Link root;
        FinalChain(Link root) { this.root = root; }
    }

    private static class ConstructorReads {
        final int field;
        final int before;
        final int after;
        ConstructorReads(int value) {
            before = readField();
            field = value;
            after = readField();
        }
        private int readField() { return field; }
    }

    private static class ThrowingConstructor {
        final int field;
        ThrowingConstructor(int value, Consumer<ThrowingConstructor> publish) {
            publish.accept(this);
            field = value;
            throw new ConstructionFailed();
        }
    }

    private static class ImplicitlyThrowingConstructor {
        final int field;
        ImplicitlyThrowingConstructor(int value, Consumer<ImplicitlyThrowingConstructor> publish) {
            publish.accept(this);
            field = value;
            failConstruction();
        }
        private void failConstruction() { throw new ConstructionFailed(); }
    }

    private static class ConstructionFailed extends RuntimeException { }
}
