/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

/**
 * Synchronization algebra describes how event labels can synchronize to form new labels using [synchronize] method.
 * For example, write access label can synchronize with read-request label
 * to form a read-response label. The response label takes value written by the write access
 * as its read value. When appropriate, we use notation `\+` to denote synchronization binary operation:
 *
 * ```
 * Write(x, v) \+ Read^{req}(x) = Read^{rsp}(x, v)
 * ```
 *
 * Synchronize operation is expected to be associative
 * (and commutative in case of [CommutativeSynchronizationAlgebra]).
 * It is partial operation --- some labels cannot participate in
 * synchronization (e.g. write access label cannot synchronize with another write access label).
 * In such cases [synchronize] returns null.
 *
 * ```
 * Write(x, v) \+ Write(y, u) = null
 * ```
 *
 * In case when a pair of labels can synchronize we also say that they are synchronizable.
 * Given a pair of synchronizable labels, we say that
 * these labels synchronize-into the synchronization result label.
 * Method [synchronizesInto] implements this relation.
 * We use notation `\>>` to denote the synchronize-into relation and `<</` to denote synchronized-from relation.
 * Therefore, if `A \+ B = C` then `A \>> C` and `B \>> C`.
 *
 * In the case of non-trivial synchronization it is also necessary to override [synchronizesInto] method,
 * because its implementation should be consistent with [synchronize]
 * It is not obligatory to override [synchronizable] method, because the default implementation
 * is guaranteed to be consistent with [synchronize] (it just checks that result of [synchronize] is not null).
 * However, the overridden implementation can optimize this check.
 *
 * Formally, synchronization algebra is the special algebraic structure deriving from partial commutative monoid [1].
 * The synchronizes-into relation corresponds to the irreflexive kernel of
 * the divisibility pre-order associated with the synchronization monoid.
 *
 * [1] Winskel, Glynn. "Event structure semantics for CCS and related languages."
 *     International Colloquium on Automata, Languages, and Programming. Springer, Berlin, Heidelberg, 1982.
 *
 */
interface SynchronizationAlgebra {

    /**
     * The synchronization type of this label.
     *
     * @see SynchronizationType
     */
    fun syncType(label: EventLabel): SynchronizationType?

    /**
     * Synchronizes two event labels.
     *
     * @return label representing result of synchronization
     *   or null if this label cannot synchronize with [label].
     */
    fun synchronize(label: EventLabel, other: EventLabel): EventLabel?

    /**
     * Checks whether two labels can synchronize.
     * Default implementation just checks that result of [synchronize] is not null,
     * overridden implementation can optimize this check.
     */
    fun synchronizable(label: EventLabel, other: EventLabel): Boolean =
        (synchronize(label, other) != null)

    /**
     * Checks whether the first label [label] synchronizes-into the second label [other],
     * i.e. there exists another label which can be synchronized with the first label to produce the second.
     */
    fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean

    // TODO: make synchronization algebras cancellative and splittable PCM?
    //   With these properties we can define `split` function that returns
    //   a unique decomposition of any given label.
    //   Then we can derive implementation of `synchronizesInto` function.
    //   To do this we need to guarantee unique decomposition,
    //   currently it does not always hold
    //   (e.g. because of InitializationLabel synchronizing with ReadLabel).
    //   We need to apply some tricks to overcome this.
}

fun SynchronizationAlgebra.synchronize(label: EventLabel?, other: EventLabel?): EventLabel? = when {
    label == null -> other
    other == null -> label
    else -> synchronize(label, other)
}

fun SynchronizationAlgebra.synchronize(events: List<Event>): EventLabel? {
    if (events.isEmpty())
        return null
    return events.fold (null) { label: EventLabel?, event ->
        synchronize(label, event.label)
    }
}


/**
 * A commutative synchronization algebra --- its [synchronize] operation is expected to be commutative.
 */
interface CommutativeSynchronizationAlgebra : SynchronizationAlgebra

/**
 * Constructs commutative synchronization algebra based on non-commutative one
 * by trying to apply [synchronize] operation in both directions.
 */
fun CommutativeSynchronizationAlgebra(algebra: SynchronizationAlgebra) = object : CommutativeSynchronizationAlgebra {
    val algebra = algebra

    override fun syncType(label: EventLabel): SynchronizationType? =
        algebra.syncType(label)

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? =
        algebra.synchronize(label, other) ?: algebra.synchronize(other, label)

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean =
        algebra.synchronizesInto(label, other)

}

/**
 * Checks whether this label has binary synchronization.
 */
fun SynchronizationAlgebra.isBinarySynchronizing(label: EventLabel): Boolean =
    (syncType(label) == SynchronizationType.Binary)

/**
 * Checks whether this label has barrier synchronization.
 */
fun SynchronizationAlgebra.isBarrierSynchronizing(label: EventLabel): Boolean =
    (syncType(label) == SynchronizationType.Barrier)

/**
 * Type of synchronization used by label.
 * Currently, two types of synchronization are supported.
 *
 * - [SynchronizationType.Binary] binary synchronization ---
 *   only a pair of events can synchronize. For example,
 *   write access label can synchronize with read-request label,
 *   but the resulting read-response label can no longer synchronize with
 *   any other label.
 *
 * - [SynchronizationType.Barrier] barrier synchronization ---
 *   a set of events can synchronize. For example,
 *   several thread finish labels can synchronize with a single thread
 *   join-request label waiting for all of these threads to complete.
 *
 * @see [EventLabel]
 */
enum class SynchronizationType { Binary, Barrier }

private val ThreadSynchronizationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when (label) {
        is ThreadForkLabel      -> SynchronizationType.Binary
        is ThreadStartLabel     -> SynchronizationType.Binary
        is ThreadFinishLabel    -> SynchronizationType.Barrier
        is ThreadJoinLabel      -> SynchronizationType.Barrier
        else                    -> null
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        other is ThreadStartLabel && other.isRequest ->
            other.getResponse(label)
        other is ThreadJoinLabel ->
            other.getResponse(label)
        other is ThreadFinishLabel ->
            other.join(label)
        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        other is ThreadStartLabel && other.isResponse ->
            other.isValidResponse(label)
        other is ThreadJoinLabel && other.isResponse ->
            other.isValidResponse(label)
        other is ThreadFinishLabel ->
            other.subsumes(label)
        else -> false
    }

}

private val MemoryAccessSynchronizationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is MemoryAccessLabel        -> SynchronizationType.Binary
        is ObjectAllocationLabel    -> SynchronizationType.Binary
        else                        -> null
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        other is ReadAccessLabel && other.isRequest ->
            other.getResponse(label)
        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        other is ReadAccessLabel && other.isResponse ->
            other.isValidResponse(label)
        else -> false
    }

}

private val MutexSynchronizationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is MutexLabel               -> SynchronizationType.Binary
        is ObjectAllocationLabel    -> SynchronizationType.Binary
        else                        -> null
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        other is LockLabel && other.isRequest ->
            other.getResponse(label)
        other is WaitLabel && other.isRequest ->
            other.getResponse(label)
        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        other is LockLabel && other.isResponse ->
            other.isValidResponse(label)
        other is WaitLabel && other.isResponse ->
            other.isValidResponse(label)
        else -> false
    }

}

private val ParkingSynchronizationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is ParkingEventLabel -> SynchronizationType.Binary
        else                 -> null
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        other is ParkLabel && other.isRequest ->
            other.getResponse(label)
        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        other is ParkLabel && other.isResponse ->
            other.isValidResponse(label)
        else -> false
    }

}

private val CoroutineSynchronizationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is CoroutineLabel -> SynchronizationType.Binary
        else              -> null
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        other is CoroutineSuspendLabel && other.isRequest ->
            other.getResponse(label)
        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        other is CoroutineSuspendLabel && other.isResponse ->
            other.isValidResponse(label)
        else -> false
    }

}

val AtomicSynchronizationAlgebra = CommutativeSynchronizationAlgebra(object : SynchronizationAlgebra {

    // TODO: generalize this construction to arbitrary list of disjoint algebras (?)

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is ThreadEventLabel         -> ThreadSynchronizationAlgebra.syncType(label)
        is MemoryAccessLabel        -> MemoryAccessSynchronizationAlgebra.syncType(label)
        is MutexLabel               -> MutexSynchronizationAlgebra.syncType(label)
        is ParkingEventLabel        -> ParkingSynchronizationAlgebra.syncType(label)
        is CoroutineLabel           -> CoroutineSynchronizationAlgebra.syncType(label)
        // special treatment of ObjectAllocationLabel, because it can contribute to several sub-algebras
        // TODO: handle such cases uniformly --- check that all algebras are either disjoint or agree with each other
        is ObjectAllocationLabel    -> SynchronizationType.Binary
        else                        -> null
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when (other) {
        is ThreadEventLabel     -> ThreadSynchronizationAlgebra.synchronize(label, other)
        is MemoryAccessLabel    -> MemoryAccessSynchronizationAlgebra.synchronize(label, other)
        is MutexLabel           -> MutexSynchronizationAlgebra.synchronize(label, other)
        is ParkLabel            -> ParkingSynchronizationAlgebra.synchronize(label, other)
        is CoroutineLabel       -> CoroutineSynchronizationAlgebra.synchronize(label, other)
        else                    -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when (other) {
        is ThreadEventLabel     -> ThreadSynchronizationAlgebra.synchronizesInto(label, other)
        is MemoryAccessLabel    -> MemoryAccessSynchronizationAlgebra.synchronizesInto(label, other)
        is MutexLabel           -> MutexSynchronizationAlgebra.synchronizesInto(label, other)
        is ParkLabel            -> ParkingSynchronizationAlgebra.synchronizesInto(label, other)
        is CoroutineLabel       -> CoroutineSynchronizationAlgebra.synchronizesInto(label, other)
        else                    -> false
    }

})

private val ReceiveAggregationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? =
        if (label.isRequest || label.isResponse) SynchronizationType.Binary else null

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        label.isRequest && other.isResponse && other.isValidResponse(label) ->
            other.getReceive()
        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        (label.isRequest || label.isResponse) && other.isReceive ->
            other.isValidReceive(label)
        else -> false
    }

}

private val MemoryAccessAggregationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is MemoryAccessLabel        -> SynchronizationType.Binary
        else                        -> null
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // read request synchronizes with read response
        label is ReadAccessLabel && label.isRequest && other is ReadAccessLabel && other.isResponse
                && other.isValidResponse(label) ->
            other.getReceive()

        // exclusive read response/receive synchronizes with exclusive write
        label is ReadAccessLabel && (label.isResponse || label.isReceive) && other is WriteAccessLabel ->
            ReadModifyWriteAccessLabel(label, other)

        // exclusive read request synchronizes with read-modify-write response
        label is ReadAccessLabel && label.isRequest && other is ReadModifyWriteAccessLabel && other.isResponse
                && label.isValidReadPart(other) ->
            other.getReceive()

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        // read request/response can produce read receive access
        other is ReadAccessLabel && other.isReceive ->
            other.isValidReceive(label)

        // read request/receive can produce read-modify-write receive access
        label is ReadAccessLabel && other is ReadModifyWriteAccessLabel && other.isReceive ->
            (label.isRequest || label.isReceive) && label.isValidReadPart(other)

        // write can produce read-modify-write receive access
        label is WriteAccessLabel && other is ReadModifyWriteAccessLabel && other.isReceive ->
            label.isValidWritePart(other)

        // read-modify-write response can produce read-modify-write receive access
        label is ReadModifyWriteAccessLabel && other is ReadModifyWriteAccessLabel && other.isReceive ->
            other.isValidReceive(label)

        else -> false
    }

}

val MutexAggregationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when (label) {
        is MutexLabel   -> SynchronizationType.Binary
        else            -> null
    }

    // TODO: use `join` and `subsumes` (?)
    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // unlock label can be merged with the subsequent wait request
        label is UnlockLabel && other is WaitLabel && other.isRequest && !other.unlocking
                && label.mutex == other.mutex ->
            WaitLabel(LabelKind.Request, label.mutex, unlocking = true)

        // wait response label can be merged with the subsequent lock request
        label is WaitLabel && label.isResponse && !label.locking && other is LockLabel && other.isRequest
                && label.mutex == other.mutex ->
            WaitLabel(LabelKind.Response, label.mutex, locking = true)

        // TODO: do we need to merge lock request/response (?)
        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        // unlock can produce unlocking wait request
        label is UnlockLabel && other is WaitLabel && other.isRequest ->
            other.unlocking

        // lock request can produce locking wait response
        label is LockLabel && label.isRequest && other is WaitLabel && other.isResponse ->
            other.locking

        // wait request can produce unlocking wait request
        label is WaitLabel && label.isRequest && other is WaitLabel && other.isRequest ->
            !label.unlocking && other.unlocking

        // wait response can produce locking wait response
        label is WaitLabel && label.isResponse && other is WaitLabel && other.isResponse ->
            !label.locking && other.locking

        else -> false
    }

}

val ThreadAggregationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when (label) {
        is MemoryAccessLabel    -> MemoryAccessAggregationAlgebra.syncType(label)
        is MutexLabel           -> MutexAggregationAlgebra.syncType(label)
        is ActorLabel           -> null
        else                    -> ReceiveAggregationAlgebra.syncType(label)
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when (label) {
        is MemoryAccessLabel    -> MemoryAccessAggregationAlgebra.synchronize(label, other)
        is MutexLabel           -> MutexSynchronizationAlgebra.synchronize(label, other)
        is ActorLabel           -> null
        else                    -> ReceiveAggregationAlgebra.synchronize(label, other)
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when (label) {
        is MemoryAccessLabel    -> MemoryAccessAggregationAlgebra.synchronizesInto(label, other)
        is MutexLabel           -> MutexSynchronizationAlgebra.synchronizesInto(label, other)
        is ActorLabel           -> false
        else                    -> ReceiveAggregationAlgebra.synchronizesInto(label, other)
    }

}
