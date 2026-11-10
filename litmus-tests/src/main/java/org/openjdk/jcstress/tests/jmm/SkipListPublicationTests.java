/*
 * Copyright (C) 2026 JetBrains s.r.o.
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openjdk.jcstress.tests.jmm;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Publication and deletion-marker patterns adapted from ConcurrentSkipListMap.
 * Integer keys and values distinguish initialized state (1) from allocation defaults (0).
 * Controls vary publication mode, fence placement, final-field use and key-dependent value reads;
 * in Control variants, a zero key skips the value read and records -2.
 * The marker pair checks whether helping an unlink can lose the live successor.
 */
public class SkipListPublicationTests {
    private static VarHandle slotHandle(Class<?> owner, Class<?> type) {
        try {
            return MethodHandles.lookup().findVarHandle(owner, "slot", type);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static class MixedNode {
        final int key;
        int value;

        MixedNode(int key, int value) {
            this.key = key;
            this.value = value;
        }
    }

    private static class PlainNode {
        int key;
        int value;

        PlainNode(int key, int value) {
            this.key = key;
            this.value = value;
        }
    }

    private static class Payload {
        int value;

        Payload(int value) {
            this.value = value;
        }
    }

    private static class FinalReferenceHolder {
        final Payload payload;

        FinalReferenceHolder(Payload payload) {
            this.payload = payload;
        }
    }

    private static class Link {
        private static final VarHandle NEXT;

        static {
            try {
                NEXT = MethodHandles.lookup().findVarHandle(Link.class, "next", Link.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final int key;
        Link next;

        Link(int key, Link next) {
            this.key = key;
            this.next = next;
        }
    }

    /** CAS publication; acquire fence precedes the plain node read, then final key and plain value reads. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class CasFenceBeforeRead {
        private static final VarHandle SLOT = slotHandle(CasFenceBeforeRead.class, MixedNode.class);
        private MixedNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            SLOT.compareAndSet(this, null, new MixedNode(1, 1));
        }

        @Actor
        public void actor2() {
            VarHandle.acquireFence();
            MixedNode node = slot;
            if (node != null) {
                int key = node.key;
                int value = node.value;
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** CAS publication and pre-read acquire fence; a final-key check guards the sibling value read. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class CasFenceBeforeReadControl {
        private static final VarHandle SLOT = slotHandle(CasFenceBeforeReadControl.class, MixedNode.class);
        private MixedNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            SLOT.compareAndSet(this, null, new MixedNode(1, 1));
        }

        @Actor
        public void actor2() {
            VarHandle.acquireFence();
            MixedNode node = slot;
            if (node != null) {
                int key = node.key;
                int value = -2;
                if (key != 0) {
                    value = node.value;
                }
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** Same CAS and fence placement, but reads only the value without consuming the final key. */
    @JCStressTest
    @State
    @Outcome(id = "0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class CasFenceBeforeValueOnly {
        private static final VarHandle SLOT = slotHandle(CasFenceBeforeValueOnly.class, MixedNode.class);
        private MixedNode slot;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            SLOT.compareAndSet(this, null, new MixedNode(1, 1));
        }

        @Actor
        public void actor2() {
            VarHandle.acquireFence();
            MixedNode node = slot;
            if (node != null) {
                observedValue = node.value;
            }
        }

        @Arbiter
        public void collect(I_Result result) {
            result.r1 = observedValue;
        }
    }

    /** Moves the acquire fence after the plain node read and before the field reads. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class CasFenceAfterRead {
        private static final VarHandle SLOT = slotHandle(CasFenceAfterRead.class, MixedNode.class);
        private MixedNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            SLOT.compareAndSet(this, null, new MixedNode(1, 1));
        }

        @Actor
        public void actor2() {
            MixedNode node = slot;
            VarHandle.acquireFence();
            if (node != null) {
                int key = node.key;
                int value = node.value;
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** Post-read acquire fence; a final-key check guards the sibling value read. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class CasFenceAfterReadControl {
        private static final VarHandle SLOT = slotHandle(CasFenceAfterReadControl.class, MixedNode.class);
        private MixedNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            SLOT.compareAndSet(this, null, new MixedNode(1, 1));
        }

        @Actor
        public void actor2() {
            MixedNode node = slot;
            VarHandle.acquireFence();
            if (node != null) {
                int key = node.key;
                int value = -2;
                if (key != 0) {
                    value = node.value;
                }
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** Replaces the plain node read and separate fence with an acquire read. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class CasAcquireRead {
        private static final VarHandle SLOT = slotHandle(CasAcquireRead.class, MixedNode.class);
        private MixedNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            SLOT.compareAndSet(this, null, new MixedNode(1, 1));
        }

        @Actor
        public void actor2() {
            MixedNode node = (MixedNode) SLOT.getAcquire(this);
            if (node != null) {
                int key = node.key;
                int value = node.value;
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** Acquire node read; a final-key check guards the sibling value read. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class CasAcquireReadControl {
        private static final VarHandle SLOT = slotHandle(CasAcquireReadControl.class, MixedNode.class);
        private MixedNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            SLOT.compareAndSet(this, null, new MixedNode(1, 1));
        }

        @Actor
        public void actor2() {
            MixedNode node = (MixedNode) SLOT.getAcquire(this);
            if (node != null) {
                int key = node.key;
                int value = -2;
                if (key != 0) {
                    value = node.value;
                }
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** Publishes with a plain store; reads the final key followed by its plain sibling value. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class PlainFinalKey {
        private MixedNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            slot = new MixedNode(1, 1);
        }

        @Actor
        public void actor2() {
            MixedNode node = slot;
            if (node != null) {
                int key = node.key;
                int value = node.value;
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** Plain publication; a final-key check guards the sibling value read. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class PlainFinalKeyControl {
        private MixedNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            slot = new MixedNode(1, 1);
        }

        @Actor
        public void actor2() {
            MixedNode node = slot;
            if (node != null) {
                int key = node.key;
                int value = -2;
                if (key != 0) {
                    value = node.value;
                }
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** Publishes a holder plainly; reads the payload through its final reference rather than a sibling key. */
    @JCStressTest
    @State
    @Outcome(id = "0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class PlainFinalReference {
        private FinalReferenceHolder slot;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            Payload payload = new Payload(1);
            slot = new FinalReferenceHolder(payload);
        }

        @Actor
        public void actor2() {
            FinalReferenceHolder holder = slot;
            if (holder != null) {
                Payload payload = holder.payload;
                observedValue = payload == null ? -2 : payload.value;
            }
        }

        @Arbiter
        public void collect(I_Result result) {
            result.r1 = observedValue;
        }
    }

    /** Same CAS and fence placement as CasFenceBeforeRead, with both node fields plain. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class CasFenceBeforePlainKey {
        private static final VarHandle SLOT = slotHandle(CasFenceBeforePlainKey.class, PlainNode.class);
        private PlainNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            SLOT.compareAndSet(this, null, new PlainNode(1, 1));
        }

        @Actor
        public void actor2() {
            VarHandle.acquireFence();
            PlainNode node = slot;
            if (node != null) {
                int key = node.key;
                int value = node.value;
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** CAS and pre-read fence with a plain key; the key check guards the sibling value read. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class CasFenceBeforePlainKeyControl {
        private static final VarHandle SLOT = slotHandle(CasFenceBeforePlainKeyControl.class, PlainNode.class);
        private PlainNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            SLOT.compareAndSet(this, null, new PlainNode(1, 1));
        }

        @Actor
        public void actor2() {
            VarHandle.acquireFence();
            PlainNode node = slot;
            if (node != null) {
                int key = node.key;
                int value = -2;
                if (key != 0) {
                    value = node.value;
                }
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** Plain publication and plain key/value fields; removes final-field initialization ordering. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class PlainKey {
        private PlainNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            slot = new PlainNode(1, 1);
        }

        @Actor
        public void actor2() {
            PlainNode node = slot;
            if (node != null) {
                int key = node.key;
                int value = node.value;
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** Plain publication and plain fields; the key check guards the sibling value read. */
    @JCStressTest
    @State
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Default field value after publication.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class PlainKeyControl {
        private PlainNode slot;
        private int observedKey = -1;
        private int observedValue = -1;

        @Actor
        public void actor1() {
            slot = new PlainNode(1, 1);
        }

        @Actor
        public void actor2() {
            PlainNode node = slot;
            if (node != null) {
                int key = node.key;
                int value = -2;
                if (key != 0) {
                    value = node.value;
                }
                observedKey = key;
                observedValue = value;
            }
        }

        @Arbiter
        public void collect(II_Result result) {
            result.r1 = observedKey;
            result.r2 = observedValue;
        }
    }

    /** CAS installs a deletion marker; a helper fences before reading it and forwards its successor. */
    @JCStressTest
    @State
    @Outcome(id = "0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Lost live successor.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class MarkerFenceBeforeRead {
        private static final VarHandle SLOT = slotHandle(MarkerFenceBeforeRead.class, Link.class);
        private Link live = new Link(1, null);
        private Link removed = new Link(1, live);
        private Link slot = removed;

        @Actor
        public void actor1() {
            Link.NEXT.compareAndSet(removed, live, new Link(0, live));
        }

        @Actor
        public void actor2() {
            VarHandle.acquireFence();
            Link f = removed.next;
            if (f != null && f.key == 0) {
                Link successor = f.next;
                SLOT.compareAndSet(this, removed, successor);
            }
        }

        @Arbiter
        public void collect(I_Result result) {
            Link head = slot;
            result.r1 = head == null ? 0 : head == live ? 1 : 2;
        }
    }

    /** Same marker forwarding, with the acquire fence moved after the marker-pointer read. */
    @JCStressTest
    @State
    @Outcome(id = "0", expect = Expect.ACCEPTABLE_INTERESTING, desc = "Lost live successor.")
    @Outcome(expect = Expect.ACCEPTABLE, desc = "Observed outcome.")
    public static class MarkerFenceAfterRead {
        private static final VarHandle SLOT = slotHandle(MarkerFenceAfterRead.class, Link.class);
        private Link live = new Link(1, null);
        private Link removed = new Link(1, live);
        private Link slot = removed;

        @Actor
        public void actor1() {
            Link.NEXT.compareAndSet(removed, live, new Link(0, live));
        }

        @Actor
        public void actor2() {
            Link f = removed.next;
            VarHandle.acquireFence();
            if (f != null && f.key == 0) {
                Link successor = f.next;
                SLOT.compareAndSet(this, removed, successor);
            }
        }

        @Arbiter
        public void collect(I_Result result) {
            Link head = slot;
            result.r1 = head == null ? 0 : head == live ? 1 : 2;
        }
    }
}
