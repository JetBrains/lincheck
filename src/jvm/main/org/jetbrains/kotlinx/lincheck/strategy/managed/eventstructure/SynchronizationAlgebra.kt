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

import org.jetbrains.kotlinx.lincheck.implies

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
 * Synchronize operation is expected to be associative and commutative.
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
     * Default implementation uses directed [forwardSynchronize] method.
     *
     * @return label representing result of synchronization
     *   or null if this label cannot synchronize with [label].
     */
    fun synchronize(label: EventLabel, other: EventLabel): EventLabel? =
        forwardSynchronize(label, other) ?: forwardSynchronize(other, label)

    /**
     * Directed (non-commutative) version of synchronization operation.
     * Inheritors can only implement this function and derive default implementation of commutative version.
     */
    fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel?

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
 *   several thread finish labels can synchronize with single thread
 *   join-request label waiting for all of these threads to complete.
 *
 * @see [EventLabel]
 */
enum class SynchronizationType { Binary, Barrier }

val ThreadSynchronizationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is ThreadForkLabel      -> SynchronizationType.Binary
        is ThreadStartLabel     -> SynchronizationType.Binary
        is ThreadFinishLabel    -> SynchronizationType.Barrier
        is ThreadJoinLabel      -> SynchronizationType.Barrier
        else                    -> null
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // thread fork synchronizes with thread start request
        other is ThreadStartLabel && other.isRequest ->
            label.asThreadForkLabel()
                ?.takeIf { other.threadId in it.forkThreadIds }
                ?.let { other.getResponse() }

        // thread finish synchronizes with thread join
        (label is ThreadFinishLabel && other is ThreadJoinLabel && other.joinThreadIds.containsAll(label.finishedThreadIds)) -> {
            ThreadJoinLabel(
                kind = LabelKind.Response,
                joinThreadIds = other.joinThreadIds - label.finishedThreadIds,
            )
        }

        // two thread finish labels can synchronize and merge their ids
        (label is ThreadFinishLabel && other is ThreadFinishLabel) -> {
            ThreadFinishLabel(
                finishedThreadIds = label.finishedThreadIds + other.finishedThreadIds
            )
        }

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        // thread start request can produce thread start response
        label is ThreadStartLabel && label.isRequest && other is ThreadStartLabel && other.isResponse ->
            other.isValidResponse(label)

        // thread fork can produce thread start response
        other is ThreadStartLabel && other.isResponse ->
            label.asThreadForkLabel()?.let { other.threadId in it.forkThreadIds } ?: false

        // thread join (request or response) can produce thread join response
        label is ThreadJoinLabel && other is ThreadJoinLabel && other.isResponse ->
            label.joinThreadIds.containsAll(other.joinThreadIds)

        // thread finish can produce thread join response
        label is ThreadFinishLabel && other is ThreadJoinLabel && other.isResponse ->
            label.finishedThreadIds.all { it !in other.joinThreadIds }

        // thread finish can produce another thread finish
        label is ThreadFinishLabel && other is ThreadFinishLabel ->
            other.finishedThreadIds.containsAll(label.finishedThreadIds)

        else -> false
    }

}

val MemoryAccessSynchronizationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is MemoryAccessLabel -> SynchronizationType.Binary
        else                 -> null
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // write access synchronizes with read request access
        (other is ReadAccessLabel && other.isRequest) ->
            label.asWriteAccessLabel(other.location)
                ?.let { other.getResponse(it.value) }

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        // read request access can produce read response access
        label is ReadAccessLabel && label.isRequest && other is ReadAccessLabel && other.isResponse ->
            other.isValidResponse(label)

        // write access can produce read response access
        other is ReadAccessLabel && other.isResponse ->
            label.asWriteAccessLabel(other.location)?.let { other.canReadFrom(it) } ?: false

        else -> false
    }

}

val MutexSynchronizationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is LockLabel    -> SynchronizationType.Binary
        is UnlockLabel  -> SynchronizationType.Binary
        is WaitLabel    -> SynchronizationType.Binary
        is NotifyLabel  -> SynchronizationType.Binary
        else            -> null
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // unlock can synchronize with lock request
        other is LockLabel && other.isRequest ->
            label.asUnlockLabel(other.mutex)
                // TODO: do not add reentry lock/unlock events to the event structure!
                ?.takeIf { !it.isReentry && (other.isReentry implies it.isInitUnlock) }
                ?.let { other.getResponse() }

        // notify can synchronize with wait request
        // TODO: provide an option to enable spurious wake-ups
        other is WaitLabel && other.isRequest ->
            label.asNotifyLabel(other.mutex)
                ?.let { other.getResponse() }

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        // lock request can produce lock response
        label is LockLabel && label.isRequest && other is LockLabel && other.isResponse ->
            other.isValidResponse(label)

        // unlock can produce lock response
        label is UnlockLabel && other is LockLabel && other.isResponse
                && label.mutex == other.mutex ->
            label.asUnlockLabel(other.mutex)?.let { other.canBeUnlockedBy(it) } ?: false

        // wait request can produce wait response
        label is WaitLabel && label.isRequest && other is WaitLabel && other.isResponse ->
            other.isValidResponse(label)

        // notify label can produce wait response
        other is WaitLabel && other.isResponse ->
            label.asNotifyLabel(other.mutex) != null

        else -> false
    }

}

val ParkingSynchronizationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is ParkLabel    -> SynchronizationType.Binary
        is UnparkLabel  -> SynchronizationType.Binary
        else            -> null
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        label is UnparkLabel && other is ParkLabel && other.isRequest && label.threadId == other.threadId ->
            ParkLabel(LabelKind.Response, other.threadId)

        // TODO: provide an option to enable spurious wake-ups
        // (isRequest && label is InitializationLabel) ->
        //     ParkLabel(LabelKind.Response, threadId)

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        label is ParkLabel && label.isRequest && other is ParkLabel && other.isResponse ->
            label.threadId == other.threadId

        label is UnparkLabel && other is ParkLabel && other.isResponse ->
            label.threadId == other.threadId

        // TODO: provide an option to enable spurious wake-ups
        // label is InitializationLabel -> true

        else -> false
    }

}

val AtomicSynchronizationAlgebra = object : SynchronizationAlgebra {

    // TODO: generalize this construction to arbitrary list of disjoint algebras (?)

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is ThreadEventLabel     -> ThreadSynchronizationAlgebra.syncType(label)
        is MemoryAccessLabel    -> MemoryAccessSynchronizationAlgebra.syncType(label)
        is MutexLabel           -> MutexSynchronizationAlgebra.syncType(label)
        is ParkingEventLabel    -> ParkingSynchronizationAlgebra.syncType(label)
        else                    -> null
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when (other) {
        is ThreadEventLabel     -> ThreadSynchronizationAlgebra.forwardSynchronize(label, other)
        is MemoryAccessLabel    -> MemoryAccessSynchronizationAlgebra.forwardSynchronize(label, other)
        is MutexLabel           -> MutexSynchronizationAlgebra.forwardSynchronize(label, other)
        is ParkLabel            -> ParkingSynchronizationAlgebra.forwardSynchronize(label, other)
        else                    -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when (other) {
        is ThreadEventLabel     -> ThreadSynchronizationAlgebra.synchronizesInto(label, other)
        is MemoryAccessLabel    -> MemoryAccessSynchronizationAlgebra.synchronizesInto(label, other)
        is MutexLabel           -> MutexSynchronizationAlgebra.synchronizesInto(label, other)
        is ParkLabel            -> ParkingSynchronizationAlgebra.synchronizesInto(label, other)
        else                    -> false
    }

}

val MemoryAccessAggregationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is MemoryAccessLabel -> SynchronizationType.Binary
        else                 -> null
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // read request synchronizes with read response
        label is ReadAccessLabel && label.isRequest && other is ReadAccessLabel && other.isResponse
                && other.isValidResponse(label) ->
            other.getReceive()

        // exclusive read response/receive synchronizes with exclusive write
        label is ReadAccessLabel && (label.isResponse || label.isReceive) && other is WriteAccessLabel
                && label.location == other.location
                && label.kClass == other.kClass
                && label.isExclusive && other.isExclusive ->
            ReadModifyWriteAccessLabel(label.kind, label, other)

        // exclusive read request synchronizes with read-modify-write response
        label is ReadAccessLabel && label.isRequest && other is ReadModifyWriteAccessLabel && other.isResponse
                && label.location == other.location
                && label.kClass == other.kClass
                && label.isExclusive && other.isExclusive ->
            other.getReceive()

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        // read request/response can produce read receive access
        label is ReadAccessLabel && (label.isRequest || label.isResponse) && other is ReadAccessLabel && other.isReceive ->
            other.isValidReceive(label)

        // read request/receive can can produce read-modify-write receive access
        label is ReadAccessLabel && (label.isRequest || label.isReceive) && other is ReadModifyWriteAccessLabel && other.isReceive ->
            label.isValidReadPart(other)

        // write can can produce read-modify-write receive access
        label is WriteAccessLabel && other is ReadModifyWriteAccessLabel && other.isReceive ->
            label.isValidWritePart(other)

        else -> false
    }

}