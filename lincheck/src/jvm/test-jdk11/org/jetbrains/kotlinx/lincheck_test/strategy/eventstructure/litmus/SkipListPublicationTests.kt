/*
 * Copyright (C) 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmus

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.UNKNOWN
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.assertSame
import org.jetbrains.kotlinx.lincheck_test.strategy.eventstructure.litmusTest
import org.junit.Test
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import kotlin.concurrent.thread

/**
 * Publication and deletion-marker patterns adapted from ConcurrentSkipListMap.
 * Control variants guard the value read with a nonzero key; -2 means the guard skipped that read.
 */
class SkipListPublicationTests {

    /** CAS publication; acquire fence precedes the plain node read, then final key and plain value reads. */
    @Test
    fun CasFenceBeforeRead() {
        litmusTest(assertSame(setOf(-1 to -1, 1 to 0, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = MixedSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                MixedSlot.SLOT.compareAndSet(state, null, MixedNode(1, 1))
            }
            val reader = thread {
                VarHandle.acquireFence()
                val node = state.slot
                if (node != null) {
                    val key = node.key
                    val value = node.value
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** CAS publication and pre-read acquire fence; a final-key check guards the sibling value read. */
    @Test
    fun CasFenceBeforeReadControl() {
        litmusTest(assertSame(setOf(-1 to -1, 1 to 0, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = MixedSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                MixedSlot.SLOT.compareAndSet(state, null, MixedNode(1, 1))
            }
            val reader = thread {
                VarHandle.acquireFence()
                val node = state.slot
                if (node != null) {
                    val key = node.key
                    var value = -2
                    if (key != 0) {
                        value = node.value
                    }
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** Same CAS and fence placement, but reads only the value without consuming the final key. */
    @Test
    fun CasFenceBeforeValueOnly() {
        litmusTest(assertSame(setOf(-1, 0, 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = MixedSlot()
            var observedValue = -1
            val writer = thread {
                MixedSlot.SLOT.compareAndSet(state, null, MixedNode(1, 1))
            }
            val reader = thread {
                VarHandle.acquireFence()
                val node = state.slot
                if (node != null) {
                    observedValue = node.value
                }
            }
            writer.join()
            reader.join()
            observedValue
        }
    }

    /** Moves the acquire fence after the plain node read and before the field reads. */
    @Test
    fun CasFenceAfterRead() {
        litmusTest(assertSame(setOf(-1 to -1, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = MixedSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                MixedSlot.SLOT.compareAndSet(state, null, MixedNode(1, 1))
            }
            val reader = thread {
                val node = state.slot
                VarHandle.acquireFence()
                if (node != null) {
                    val key = node.key
                    val value = node.value
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** Post-read acquire fence; a final-key check guards the sibling value read. */
    @Test
    fun CasFenceAfterReadControl() {
        litmusTest(assertSame(setOf(-1 to -1, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = MixedSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                MixedSlot.SLOT.compareAndSet(state, null, MixedNode(1, 1))
            }
            val reader = thread {
                val node = state.slot
                VarHandle.acquireFence()
                if (node != null) {
                    val key = node.key
                    var value = -2
                    if (key != 0) {
                        value = node.value
                    }
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** Replaces the plain node read and separate fence with an acquire read. */
    @Test
    fun CasAcquireRead() {
        litmusTest(assertSame(setOf(-1 to -1, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = MixedSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                MixedSlot.SLOT.compareAndSet(state, null, MixedNode(1, 1))
            }
            val reader = thread {
                val node = MixedSlot.SLOT.getAcquire(state) as MixedNode?
                if (node != null) {
                    val key = node.key
                    val value = node.value
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** Acquire node read; a final-key check guards the sibling value read. */
    @Test
    fun CasAcquireReadControl() {
        litmusTest(assertSame(setOf(-1 to -1, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = MixedSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                MixedSlot.SLOT.compareAndSet(state, null, MixedNode(1, 1))
            }
            val reader = thread {
                val node = MixedSlot.SLOT.getAcquire(state) as MixedNode?
                if (node != null) {
                    val key = node.key
                    var value = -2
                    if (key != 0) {
                        value = node.value
                    }
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** Publishes with a plain store; reads the final key followed by its plain sibling value. */
    @Test
    fun PlainFinalKey() {
        litmusTest(assertSame(setOf(-1 to -1, 1 to 0, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = MixedSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                state.slot = MixedNode(1, 1)
            }
            val reader = thread {
                val node = state.slot
                if (node != null) {
                    val key = node.key
                    val value = node.value
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** Plain publication; a final-key check guards the sibling value read. */
    @Test
    fun PlainFinalKeyControl() {
        litmusTest(assertSame(setOf(-1 to -1, 1 to 0, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = MixedSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                state.slot = MixedNode(1, 1)
            }
            val reader = thread {
                val node = state.slot
                if (node != null) {
                    val key = node.key
                    var value = -2
                    if (key != 0) {
                        value = node.value
                    }
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** Publishes a holder plainly; reads the payload through its final reference rather than a sibling key. */
    @Test
    fun PlainFinalReference() {
        litmusTest(assertSame(setOf(-1, 1), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var slot: FinalReferenceHolder? = null
            var observedValue = -1
            val writer = thread {
                val payload = Payload(1)
                slot = FinalReferenceHolder(payload)
            }
            val reader = thread {
                val holder = slot
                if (holder != null) {
                    val payload = holder.payload
                    observedValue = payload?.value ?: -2
                }
            }
            writer.join()
            reader.join()
            observedValue
        }
    }

    /** Same CAS and fence placement as CasFenceBeforeRead, with both node fields plain. */
    @Test
    fun CasFenceBeforePlainKey() {
        litmusTest(assertSame(setOf(-1 to -1, 0 to 0, 0 to 1, 1 to 0, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = PlainSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                PlainSlot.SLOT.compareAndSet(state, null, PlainNode(1, 1))
            }
            val reader = thread {
                VarHandle.acquireFence()
                val node = state.slot
                if (node != null) {
                    val key = node.key
                    val value = node.value
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** CAS and pre-read fence with a plain key; the key check guards the sibling value read. */
    @Test
    fun CasFenceBeforePlainKeyControl() {
        litmusTest(assertSame(setOf(-1 to -1, 0 to -2, 1 to 0, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = PlainSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                PlainSlot.SLOT.compareAndSet(state, null, PlainNode(1, 1))
            }
            val reader = thread {
                VarHandle.acquireFence()
                val node = state.slot
                if (node != null) {
                    val key = node.key
                    var value = -2
                    if (key != 0) {
                        value = node.value
                    }
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** Plain publication and plain key/value fields; removes final-field initialization ordering. */
    @Test
    fun PlainKey() {
        litmusTest(assertSame(setOf(-1 to -1, 0 to 0, 0 to 1, 1 to 0, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = PlainSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                state.slot = PlainNode(1, 1)
            }
            val reader = thread {
                val node = state.slot
                if (node != null) {
                    val key = node.key
                    val value = node.value
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** Plain publication and plain fields; the key check guards the sibling value read. */
    @Test
    fun PlainKeyControl() {
        litmusTest(assertSame(setOf(-1 to -1, 0 to -2, 1 to 0, 1 to 1), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val state = PlainSlot()
            var observedKey = -1
            var observedValue = -1
            val writer = thread {
                state.slot = PlainNode(1, 1)
            }
            val reader = thread {
                val node = state.slot
                if (node != null) {
                    val key = node.key
                    var value = -2
                    if (key != 0) {
                        value = node.value
                    }
                    observedKey = key
                    observedValue = value
                }
            }
            writer.join()
            reader.join()
            observedKey to observedValue
        }
    }

    /** CAS installs a deletion marker; a helper fences before reading it and forwards its successor. */
    @Test
    fun MarkerFenceBeforeRead() {
        litmusTest(assertSame(setOf(0, 1, 2), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val live = Link(1, null)
            val removed = Link(1, live)
            val state = LinkSlot(removed)
            val writer = thread {
                Link.NEXT.compareAndSet(removed, live, Link(0, live))
            }
            val helper = thread {
                VarHandle.acquireFence()
                val f = removed.next
                if (f != null && f.key == 0) {
                    val successor = f.next
                    LinkSlot.SLOT.compareAndSet(state, removed, successor)
                }
            }
            writer.join()
            helper.join()
            val head = state.slot
            if (head == null) 0 else if (head === live) 1 else 2
        }
    }

    /** Same marker forwarding, with the acquire fence moved after the marker-pointer read. */
    @Test
    fun MarkerFenceAfterRead() {
        litmusTest(assertSame(setOf(1, 2), UNKNOWN), MemoryModel.RelaxedJava,
            requireExhaustion = true) {
            val live = Link(1, null)
            val removed = Link(1, live)
            val state = LinkSlot(removed)
            val writer = thread {
                Link.NEXT.compareAndSet(removed, live, Link(0, live))
            }
            val helper = thread {
                val f = removed.next
                VarHandle.acquireFence()
                if (f != null && f.key == 0) {
                    val successor = f.next
                    LinkSlot.SLOT.compareAndSet(state, removed, successor)
                }
            }
            writer.join()
            helper.join()
            val head = state.slot
            if (head == null) 0 else if (head === live) 1 else 2
        }
    }

    private class MixedNode(@JvmField val key: Int, @JvmField var value: Int)

    private class PlainNode(@JvmField var key: Int, @JvmField var value: Int)

    private class Payload(@JvmField var value: Int)

    private class FinalReferenceHolder(@JvmField val payload: Payload?)

    private class MixedSlot {
        @JvmField var slot: MixedNode? = null

        companion object {
            val SLOT: VarHandle = MethodHandles.lookup()
                .findVarHandle(MixedSlot::class.java, "slot", MixedNode::class.java)
        }
    }

    private class PlainSlot {
        @JvmField var slot: PlainNode? = null

        companion object {
            val SLOT: VarHandle = MethodHandles.lookup()
                .findVarHandle(PlainSlot::class.java, "slot", PlainNode::class.java)
        }
    }

    private class Link(@JvmField val key: Int, @JvmField var next: Link?) {
        companion object {
            val NEXT: VarHandle = MethodHandles.lookup().findVarHandle(Link::class.java, "next", Link::class.java)
        }
    }

    private class LinkSlot(@JvmField var slot: Link?) {
        companion object {
            val SLOT: VarHandle = MethodHandles.lookup().findVarHandle(LinkSlot::class.java, "slot", Link::class.java)
        }
    }
}
