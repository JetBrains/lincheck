/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.strategy.eventstructure

import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

/**
 * An `AtomicInteger` subclass that declares its own same-arity overloads of `get` and `set`:
 * an indexed `get(index)` (the way an array-backed atomic-like class might —
 * `io.reactivex.rxjava3.internal.util.VolatileSizeArrayList`'s element type, for one)
 * and a one-argument `set(String)`. `Class.methods` has no specified order, so resolving the atomic's
 * accessors by name — and, for `set`, by parameter count — alone can pick one of these over the real
 * `AtomicInteger.get()` / `set(int)`.
 */
private class IndexedAtomicInteger : AtomicInteger(0) {
    operator fun get(index: Int): Int = get() + index

    // A same-arity `set` overload: this `set(String)` and the inherited `set(int)` both take one argument,
    // so the real setter can be told apart only by parameter type, not by parameter count.
    fun set(value: String) = set(value.toInt())

    // Required by the Kotlin/JDK `Number` interop for a direct `AtomicInteger` subclass (see `AtomicSubclassTest`).
    override fun toByte(): Byte = get().toByte()
    override fun toShort(): Short = get().toShort()
}

/**
 * Checks that the experimental strategy resolves the real `AtomicInteger.get()` / `set(int)` and not a
 * same-arity overload a subclass happens to also declare: `Class.methods` has no specified order, so picking
 * the first method named `get`, or the first one-argument `set`, can pick the wrong overload and crash with
 * `IllegalArgumentException` when the event structure re-invokes it.
 */
class AtomicIntegerSubclassOverloadTest {

    @Test
    fun testGetResolvesTheNoArgumentOverload() {
        litmusTest(assertSame(setOf(2)), MemoryModel.SequentialConsistency) {
            val counter = IndexedAtomicInteger()
            counter.set(2)
            counter.get()
        }
    }

    @Test
    fun testSetResolvesTheCorrectlyTypedOverload() {
        // `getAndSet` records a read-modify-write whose write the event structure replays through the resolved
        // setter; with a same-arity `set(String)` present, a parameter-count-only match invokes it with an `Int`
        // and throws `IllegalArgumentException`. The block yields the previous value, 0.
        litmusTest(assertSame(setOf(0)), MemoryModel.SequentialConsistency) {
            val counter = IndexedAtomicInteger()
            counter.getAndSet(2)
        }
    }
}
