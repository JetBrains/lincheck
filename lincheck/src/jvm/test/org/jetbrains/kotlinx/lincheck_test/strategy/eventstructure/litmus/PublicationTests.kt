/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
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
import org.jetbrains.lincheck.util.UnsafeHolder
import org.jetbrains.lincheck.util.getFieldOffsetViaUnsafe
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Litmus tests for safe and unsafe publication semantics and final-field semantics. */
class PublicationTests {

    @Test
    fun FinalPrimitive() = finalPrimitive(leaksThis = false)

    @Test
    fun FinalPrimitiveWithLeak() = finalPrimitive(leaksThis = true)

    @Test
    fun FinalPrimitiveLeakedAfterAssignment() {
        // Assignment alone does not freeze the field; publication still precedes constructor exit.
        litmusTest(assertSame(setOf(-1, 0, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: AssignedLeakingFinal? = null
            var result = -1
            val writer = thread { AssignedLeakingFinal(42) { published = it } }
            val reader = thread { result = published?.field ?: -1 }
            writer.join()
            reader.join()
            result
        }
    }

    private fun finalPrimitive(
        leaksThis: Boolean,
        readViaUnsafe: Boolean = false,
        memoryModel: MemoryModel = MemoryModel.RelaxedJava,
    ) {
        val outcomes = if (leaksThis) setOf(-1, 0, 42) else setOf(-1, 42)
        litmusTest(assertSame(outcomes, UNKNOWN), memoryModel, requireExhaustion = true) {
            var published: FinalPrimitive? = null
            var result = -1
            val writer = thread {
                published = FinalPrimitive(42) { if (leaksThis) published = it }
            }
            val reader = thread {
                val observed = published
                result = when {
                    observed == null -> -1
                    readViaUnsafe -> observed.readViaUnsafe()
                    else -> observed.field
                }
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun MutablePrimitive() = mutablePrimitive(leaksThis = false)

    @Test
    fun MutablePrimitiveWithLeak() = mutablePrimitive(leaksThis = true)

    private fun mutablePrimitive(leaksThis: Boolean, readViaUnsafe: Boolean = false) {
        litmusTest(assertSame(setOf(-1, 0, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: MutablePrimitive? = null
            var result = -1
            val writer = thread {
                published = MutablePrimitive(42) { if (leaksThis) published = it }
            }
            val reader = thread {
                val observed = published
                result = if (readViaUnsafe) observed?.readViaUnsafe() ?: -1 else observed?.field ?: -1
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun FinalReference() = finalReference(leaksThis = false)

    @Test
    fun FinalReferenceWithLeak() = finalReference(leaksThis = true)

    private fun finalReference(leaksThis: Boolean, readViaUnsafe: Boolean = false) {
        val outcomes = if (leaksThis) setOf(-1, 0, 1) else setOf(-1, 1)
        litmusTest(assertSame(outcomes, UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalReference? = null
            var result = -1
            val writer = thread {
                published = FinalReference(Any()) { if (leaksThis) published = it }
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else {
                    val value = if (readViaUnsafe) observed.readViaUnsafe() else observed.field
                    if (value == null) 0 else 1
                }
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun MutableReference() = mutableReference(leaksThis = false)

    @Test
    fun MutableReferenceWithLeak() = mutableReference(leaksThis = true)

    private fun mutableReference(leaksThis: Boolean) {
        litmusTest(assertSame(setOf(-1, 0, 1), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: MutableReference? = null
            var result = -1
            val writer = thread {
                published = MutableReference(Any()) { if (leaksThis) published = it }
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else if (observed.field == null) 0 else 1
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun FinalAndMutableFields() = finalAndMutableFields(leaksThis = false)

    @Test
    fun FinalAndMutableFieldsWithLeak() = finalAndMutableFields(leaksThis = true)

    private fun finalAndMutableFields(leaksThis: Boolean) {
        val outcomes = if (leaksThis) {
            setOf(-1 to -1, 0 to 0, 0 to 42, 42 to 0, 42 to 42)
        } else {
            setOf(-1 to -1, 42 to 0, 42 to 42)
        }
        litmusTest(assertSame(outcomes, UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: MixedFields? = null
            var finalResult = -1
            var mutableResult = -1
            val writer = thread {
                published = MixedFields(42) { if (leaksThis) published = it }
            }
            val reader = thread {
                val observed = published
                if (observed != null) {
                    finalResult = observed.finalField
                    mutableResult = observed.mutableField
                }
            }
            writer.join()
            reader.join()
            finalResult to mutableResult
        }
    }

    @Test
    fun SuperclassFreezePrecedesSubclassLeak() {
        val outcomes = setOf(-1 to -1, 42 to 0, 42 to 42)
        litmusTest(assertSame(outcomes, UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: Derived? = null
            var baseResult = -1
            var derivedResult = -1
            val writer = thread { Derived(42) { published = it } }
            val reader = thread {
                val observed = published
                if (observed != null) {
                    baseResult = observed.baseField
                    derivedResult = observed.derivedField
                }
            }
            writer.join()
            reader.join()
            baseResult to derivedResult
        }
    }

    @Test
    fun DelegatingConstructorDoesNotRefreezeFinalReference() {
        // The mutable write in the outer constructor follows the freeze in the inner constructor.
        litmusTest(assertSame(setOf(-1, 1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: DelegatingConstructor? = null
            var result = -1
            val writer = thread { published = DelegatingConstructor(42) }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.cell?.value ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun HolderPublicationAfterFreezeProtectsEarlierLeak() {
        // Holder.value remains racy, but a non-null value reached through the later publication is frozen.
        litmusTest(assertSame(setOf(-2, -1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: Holder<LeakingFinal>? = null
            var result = -1
            val writer = thread {
                val holder = Holder<LeakingFinal>()
                LeakingFinal(42) { holder.value = it }
                published = holder
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.value?.field ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun PlainRelayCarriesFreezeOfPrematurelyPublishedObject() {
        litmusTest(assertSame(setOf(-1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: LeakingFinal? = null
            var forwarded: LeakingFinal? = null
            val early = Holder<LeakingFinal>()
            var result = -1
            val writer = thread {
                published = LeakingFinal(42) { early.value = it }
            }
            val relay = thread {
                val observed = published
                if (observed != null) forwarded = observed
            }
            val reader = thread {
                val observed = forwarded
                result = observed?.field ?: -1
            }
            writer.join()
            relay.join()
            reader.join()
            result
        }
    }

    @Test
    fun AcquireProtectsPreviouslyReadLeakedReference() {
        litmusTest(assertSame(setOf(-2, -1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: LeakingFinal? = null
            val ready = AtomicInteger(0)
            var result = -1
            val writer = thread {
                LeakingFinal(42) { published = it }
                ready.set(1)
            }
            val reader = thread {
                val observed = published
                if (observed != null) {
                    result = if (ready.get() == 1) observed.field else -2
                }
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun FinalReferenceProtectsMutableField() = finalReferenceProtectsMutableField(readViaUnsafe = false)

    private fun finalReferenceProtectsMutableField(readViaUnsafe: Boolean) {
        litmusTest(assertSame(setOf(-1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalCellReference? = null
            var result = -1
            val writer = thread { published = FinalCellReference(Cell(42)) }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else {
                    val cell = if (readViaUnsafe) observed.readViaUnsafe() else observed.cell
                    cell?.value ?: -2
                }
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun FinalReferenceRejectsStaleWritesAndAllowsLaterWrites() {
        litmusTest(assertSame(setOf(-1, 42, 43), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalCellReference? = null
            var result = -1
            val writer = thread {
                val cell = Cell(1)
                cell.value = 42
                published = FinalCellReference(cell)
                cell.value = 43
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.cell?.value ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun FinalReferenceProtectsWritesFromJoinedThread() {
        litmusTest(assertSame(setOf(-1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalCellReference? = null
            var result = -1
            val writer = thread {
                val cell = Cell(1)
                val initializer = thread { cell.value = 42 }
                initializer.join()
                published = FinalCellReference(cell)
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.cell?.value ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun FinalReferencePreservesConcurrentProtectedWrites() {
        litmusTest(assertSame(setOf(-1, 42, 43), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalCellReference? = null
            var result = -1
            val writer = thread {
                val cell = Cell(1)
                val first = thread { cell.value = 42 }
                val second = thread { cell.value = 43 }
                first.join()
                second.join()
                published = FinalCellReference(cell)
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.cell?.value ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun FinalReferenceRejectsStaleWriteFromAnotherThread() {
        litmusTest(assertSame(setOf(-1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalCellReference? = null
            var result = -1
            val writer = thread {
                val cell = Cell(1)
                val initializer = thread { cell.value = 2 }
                initializer.join()
                cell.value = 42
                published = FinalCellReference(cell)
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.cell?.value ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun FinalReferenceProtectsArrayElements() {
        litmusTest(assertSame(setOf(-1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalArray? = null
            var result = -1
            val writer = thread { published = FinalArray(intArrayOf(42)) }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.values?.get(0) ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun FinalReferenceProtectsTransitiveDereference() {
        litmusTest(assertSame(setOf(-1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: FinalChain? = null
            var result = -1
            val writer = thread { published = FinalChain(Link(Cell(42))) }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.root?.next?.value ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun ConstructorReadsDefaultBeforeAssignmentAndOwnWriteAfterward() {
        litmusTest(assertSame(setOf(0 to 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            val observed = ConstructorReads(42)
            observed.before to observed.after
        }
    }

    @Test
    fun AbruptConstructorExitFreezesAssignedFinalField() {
        litmusTest(assertSame(setOf(-2, -1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: Holder<ThrowingConstructor>? = null
            var result = -1
            val writer = thread {
                val holder = Holder<ThrowingConstructor>()
                try {
                    ThrowingConstructor(42) { holder.value = it }
                } catch (_: ConstructionFailed) {
                    published = holder
                }
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.value?.field ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun ExceptionFromCalledMethodFreezesAssignedFinalField() {
        litmusTest(assertSame(setOf(-2, -1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: Holder<ImplicitlyThrowingConstructor>? = null
            var result = -1
            val writer = thread {
                val holder = Holder<ImplicitlyThrowingConstructor>()
                try {
                    ImplicitlyThrowingConstructor(42) { holder.value = it }
                } catch (_: ConstructionFailed) {
                    published = holder
                }
            }
            val reader = thread {
                val observed = published
                result = if (observed == null) -1 else observed.value?.field ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun HiddenFieldsHaveDistinctWritesAndFreezes() {
        val outcomes = setOf(Triple(-1, -1, -1), Triple(41, 0, 41), Triple(41, 42, 41))
        litmusTest(assertSame(outcomes, UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: HiddenFields? = null
            var baseResult = -1
            var derivedResult = -1
            var inheritedResult = -1
            val writer = thread { HiddenFields(41, 42) { published = it } }
            val reader = thread {
                val observed = published
                if (observed != null) {
                    baseResult = observed.baseValue()
                    derivedResult = observed.derivedValue()
                    inheritedResult = observed.inheritedValue()
                }
            }
            writer.join()
            reader.join()
            Triple(baseResult, derivedResult, inheritedResult)
        }
    }

    @Test
    fun CompletedSubclassProtectsBothHiddenFields() {
        val outcomes = setOf(-1 to -1, 41 to 42)
        litmusTest(assertSame(outcomes, UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var published: HiddenFields? = null
            var baseResult = -1
            var derivedResult = -1
            val writer = thread { published = HiddenFields(41, 42) {} }
            val reader = thread {
                val observed = published
                if (observed != null) {
                    baseResult = observed.baseValue()
                    derivedResult = observed.derivedValue()
                }
            }
            writer.join()
            reader.join()
            baseResult to derivedResult
        }
    }

    @Test
    fun RepeatedReferenceReadRetainsEarlierFreezeView() {
        // The cumulative approximation retains the first observation when the same object is read again.
        // This narrows the outcomes without adding an ordinary HB edge from the freeze.
        litmusTest(assertSame(setOf(-2, -1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var early: LeakingFinal? = null
            var published: LeakingFinal? = null
            val handoff = AtomicReference<LeakingFinal?>(null)
            var result = -1
            val writer = thread {
                published = LeakingFinal(42) { early = it }
            }
            val relay = thread {
                val observed = published
                if (observed != null) handoff.set(observed)
            }
            val reader = thread {
                if (handoff.get() != null) {
                    val observed = early
                    result = observed?.field ?: -2
                }
            }
            writer.join()
            relay.join()
            reader.join()
            result
        }
    }

    @Test
    fun PlainRepeatedReferenceReadRetainsEarlierFreezeView() {
        // This records the cumulative-reference approximation, not an actual address dependency from `published`.
        val outcomes = setOf(0 to -1, 0 to 0, 0 to 42, 1 to -1, 1 to 42)
        litmusTest(assertSame(outcomes, UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var early: LeakingFinal? = null
            var published: LeakingFinal? = null
            var sawPublished = -1
            var result = -1
            val writer = thread { published = LeakingFinal(42) { early = it } }
            val reader = thread {
                val laterReference = published
                val earlierReference = early
                sawPublished = if (laterReference == null) 0 else 1
                result = earlierReference?.field ?: -1
            }
            writer.join()
            reader.join()
            sawPublished to result
        }
    }

    @Test
    fun RepeatedReferenceWriteCarriesAccumulatedView() {
        litmusTest(assertSame(setOf(-1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var early: LeakingFinal? = null
            var published: LeakingFinal? = null
            var forwarded: LeakingFinal? = null
            var result = -1
            val writer = thread { published = LeakingFinal(42) { early = it } }
            val relay = thread {
                if (published != null) forwarded = early
            }
            val reader = thread { result = forwarded?.field ?: -1 }
            writer.join()
            relay.join()
            reader.join()
            result
        }
    }

    @Test
    fun RepeatedAliasRetainsFinalReferenceProtection() {
        litmusTest(assertSame(setOf(-2, -1, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var bare: Cell? = null
            var published: FinalCellReference? = null
            var result = -1
            val writer = thread {
                val cell = Cell(42)
                bare = cell
                published = FinalCellReference(cell)
            }
            val reader = thread {
                val observed = published
                if (observed != null) {
                    result = if (observed.cell == null) -3 else bare?.value ?: -2
                }
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun UnrelatedReferenceDoesNotAccumulateFreezeView() {
        litmusTest(assertSame(setOf(-2, -1, 0, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var early: LeakingFinal? = null
            var unrelated: Any? = null
            var result = -1
            val writer = thread {
                LeakingFinal(42) { early = it }
                unrelated = Any()
            }
            val reader = thread {
                if (unrelated != null) result = early?.field ?: -2
            }
            writer.join()
            reader.join()
            result
        }
    }

    @Test
    fun SameReferenceViewsRemainThreadLocal() {
        litmusTest(assertSame(setOf(-2, -1, 0, 42), UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var early: LeakingFinal? = null
            var published: LeakingFinal? = null
            var observed = 0
            var result = -1
            val writer = thread { published = LeakingFinal(42) { early = it } }
            val observer = thread {
                if (published != null) observed = 1
            }
            val reader = thread {
                if (observed == 1) result = early?.field ?: -2
            }
            writer.join()
            observer.join()
            reader.join()
            result
        }
    }

    @Test
    fun LaterReferenceViewDoesNotProtectEarlierRead() {
        val outcomes = setOf(-1 to -1, -1 to 42, 0 to -1, 0 to 42, 42 to -1, 42 to 42)
        litmusTest(assertSame(outcomes, UNKNOWN), MemoryModel.RelaxedJava, requireExhaustion = true) {
            var early: LeakingFinal? = null
            var published: LeakingFinal? = null
            var before = -1
            var after = -1
            val writer = thread { published = LeakingFinal(42) { early = it } }
            val reader = thread {
                before = early?.field ?: -1
                after = published?.field ?: -1
            }
            writer.join()
            reader.join()
            before to after
        }
    }

    private class FinalPrimitive(value: Int, publish: (FinalPrimitive) -> Unit) {
        companion object {
            private val fieldOffset = getFieldOffsetViaUnsafe(FinalPrimitive::class.java.getDeclaredField("field"))
        }

        val field: Int

        init {
            publish(this)
            field = value
        }

        fun readViaUnsafe(): Int = UnsafeHolder.UNSAFE.getInt(this, fieldOffset)
    }

    private class MutablePrimitive(value: Int, publish: (MutablePrimitive) -> Unit) {
        companion object {
            private val fieldOffset = getFieldOffsetViaUnsafe(MutablePrimitive::class.java.getDeclaredField("field"))
        }

        var field: Int

        init {
            publish(this)
            field = value
        }

        fun readViaUnsafe(): Int = UnsafeHolder.UNSAFE.getInt(this, fieldOffset)
    }

    private class FinalReference(value: Any, publish: (FinalReference) -> Unit) {
        companion object {
            private val fieldOffset = getFieldOffsetViaUnsafe(FinalReference::class.java.getDeclaredField("field"))
        }

        val field: Any?

        init {
            publish(this)
            field = value
        }

        fun readViaUnsafe(): Any? = UnsafeHolder.UNSAFE.getObject(this, fieldOffset)
    }

    private class MutableReference(value: Any, publish: (MutableReference) -> Unit) {
        var field: Any?

        init {
            publish(this)
            field = value
        }
    }

    private class MixedFields(value: Int, publish: (MixedFields) -> Unit) {
        val finalField: Int
        var mutableField: Int

        init {
            publish(this)
            finalField = value
            mutableField = value
        }
    }

    private open class Base(value: Int) {
        val baseField = value
    }

    private class Derived(value: Int, publish: (Derived) -> Unit) : Base(value) {
        val derivedField: Int

        init {
            publish(this)
            derivedField = value
        }
    }

    // Private properties let Kotlin keep distinct backing fields with the same names in the hierarchy.
    private open class HiddenGrandBase(private val inherited: Int)

    private open class HiddenBase(value: Int) : HiddenGrandBase(99) {
        private val value = value
        @JvmField protected val inherited = value

        fun baseValue(): Int = value
    }

    private class HiddenFields(baseValue: Int, derivedValue: Int, publish: (HiddenFields) -> Unit) : HiddenBase(baseValue) {
        private val value: Int

        init {
            publish(this)
            value = derivedValue
        }

        fun derivedValue(): Int = value

        fun inheritedValue(): Int = inherited
    }

    private class DelegatingConstructor private constructor(val cell: Cell?) {
        constructor(value: Int) : this(Cell(1)) {
            cell!!.value = value
        }
    }

    private class LeakingFinal(value: Int, publish: (LeakingFinal) -> Unit) {
        val field: Int

        init {
            publish(this)
            field = value
        }
    }

    private class AssignedLeakingFinal(value: Int, publish: (AssignedLeakingFinal) -> Unit) {
        val field: Int = value

        init {
            publish(this)
        }
    }

    private class Holder<T> {
        var value: T? = null
    }

    private class Cell(var value: Int)

    private class Link(var next: Cell?)

    private class FinalCellReference(val cell: Cell?) {
        companion object {
            private val cellOffset = getFieldOffsetViaUnsafe(FinalCellReference::class.java.getDeclaredField("cell"))
        }

        fun readViaUnsafe(): Cell? = UnsafeHolder.UNSAFE.getObject(this, cellOffset) as Cell?
    }

    private class FinalArray(val values: IntArray?)

    private class FinalChain(val root: Link?)

    private class ConstructorReads(value: Int) {
        val field: Int
        val before: Int
        val after: Int

        init {
            before = readField()
            field = value
            after = readField()
        }

        private fun readField() = field
    }

    private class ThrowingConstructor(value: Int, publish: (ThrowingConstructor) -> Unit) {
        val field: Int = run {
            publish(this)
            value
        }

        init {
            throw ConstructionFailed()
        }
    }

    private class ImplicitlyThrowingConstructor(value: Int, publish: (ImplicitlyThrowingConstructor) -> Unit) {
        val field: Int

        init {
            publish(this)
            field = value
            failConstruction()
        }

        private fun failConstruction() {
            throw ConstructionFailed()
        }
    }

    private class ConstructionFailed : RuntimeException()
}
