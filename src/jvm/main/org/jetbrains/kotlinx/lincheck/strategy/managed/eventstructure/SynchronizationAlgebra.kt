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

import org.jetbrains.kotlinx.lincheck.strategy.managed.OpaqueValue

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

class ThreadForkSynchronizationAlgebra : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is ThreadForkLabel  -> SynchronizationType.Binary
        is ThreadStartLabel -> SynchronizationType.Binary
        else                -> null
    }

    private fun ThreadStartLabel.getResponse(): ThreadStartLabel {
        require(isRequest)
        return ThreadStartLabel(
            kind = LabelKind.Response,
            threadId = threadId,
            isMainThread = isMainThread,
        )
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // TODO: get rid of this special case
        label is InitializationLabel && other is ThreadStartLabel && other.isRequest && other.isMainThread ->
            other.getResponse()

        label is ThreadForkLabel && other is ThreadStartLabel && other.isRequest && other.threadId in label.forkThreadIds ->
            other.getResponse()

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        // TODO: get rid of this special case
        label is InitializationLabel && other is ThreadStartLabel && other.isResponse
                && other.isMainThread ->
            true

        label is ThreadStartLabel && label.isRequest && other is ThreadStartLabel && other.isResponse
                && label.threadId == other.threadId ->
            true

        label is ThreadForkLabel && other is ThreadStartLabel && other.isResponse
                && other.threadId in label.forkThreadIds ->
            true

        else -> false
    }

}

class ThreadJoinSynchronizationAlgebra : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is ThreadJoinLabel      -> SynchronizationType.Barrier
        is ThreadFinishLabel    -> SynchronizationType.Barrier
        else                    -> null
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // TODO: handle cases of invalid synchronization:
        //  - when there are multiple ThreadFinish labels with the same thread id
        //  - when thread finishes outside of matching ThreadFork/ThreadJoin scope
        //  In order to handle the last case we need to add `scope` parameter to Thread labels?.
        //  Throw `InvalidBarrierSynchronizationException` in these cases.
        (label is ThreadFinishLabel && other is ThreadJoinLabel && other.joinThreadIds.containsAll(label.finishedThreadIds)) -> {
            ThreadJoinLabel(
                kind = LabelKind.Response,
                joinThreadIds = other.joinThreadIds - label.finishedThreadIds,
            )
        }

        (label is ThreadFinishLabel && other is ThreadFinishLabel) -> {
            ThreadFinishLabel(
                finishedThreadIds = label.finishedThreadIds + other.finishedThreadIds
            )
        }

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        label is ThreadJoinLabel && label.isRequest && other is ThreadJoinLabel && other.isResponse
                && label.joinThreadIds.containsAll(other.joinThreadIds) ->
            true

        label is ThreadFinishLabel && other is ThreadJoinLabel && other.isResponse
                && label.finishedThreadIds.all { it !in other.joinThreadIds } ->
            true

        label is ThreadFinishLabel && other is ThreadFinishLabel
                && other.finishedThreadIds.containsAll(label.finishedThreadIds) ->
            true

        else -> false
    }

}

class MemoryAccessSynchronizationAlgebra : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is MemoryAccessLabel -> SynchronizationType.Binary
        else                 -> null
    }

    private fun ReadAccessLabel.getResponse(value: OpaqueValue?): ReadAccessLabel {
        require(isRequest)
        // TODO: perform dynamic type-check
        // require(value.isInstanceOf(kClass))
        return ReadAccessLabel(
            kind = LabelKind.Response,
            location_ = location,
            kClass = kClass,
            value_ = value,
            isExclusive = isExclusive,
        )
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        (other is ReadAccessLabel && other.isRequest) ->
            label.asWriteAccessLabel(other.location)?.let { other.getResponse(it.value) }

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        label is ReadAccessLabel && label.isRequest && other is ReadAccessLabel && other.isResponse
                && label.kClass == other.kClass
                && label.location == other.location
                && label.isExclusive == other.isExclusive ->
            true

        other is ReadAccessLabel && other.isResponse && (label.asWriteAccessLabel(other.location)?.let {
            // TODO: also check kClass
            it.value == other.value
        } ?: false) ->
            true

        else -> false
    }

}

class LockSynchronizationAlgebra : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is LockLabel    -> SynchronizationType.Binary
        is UnlockLabel  -> SynchronizationType.Binary
        else            -> null
    }

    private fun LockLabel.getResponse(): LockLabel {
        require(isRequest)
        return LockLabel(
            kind = LabelKind.Response,
            mutex_ = mutex,
            reentranceDepth = reentranceDepth,
            reentranceCount = reentranceCount,
        )
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        label is UnlockLabel && !label.isReentry && other is LockLabel && other.isRequest && !other.isReentry
                && label.mutex == other.mutex ->
            other.getResponse()

        label is InitializationLabel && other is LockLabel && other.isRequest ->
            other.getResponse()

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        label is LockLabel && label.isRequest && other is LockLabel && other.isResponse
                && label.mutex == other.mutex ->
            true

        label is UnlockLabel && other is LockLabel && other.isResponse && !other.isReentry
                && label.mutex == other.mutex ->
            true

        label is InitializationLabel && other is LockLabel && other.isResponse ->
            true

        else -> false
    }

}

class WaitSynchronizationAlgebra : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is WaitLabel    -> SynchronizationType.Binary
        is NotifyLabel  -> SynchronizationType.Binary
        else            -> null
    }

    private fun WaitLabel.getResponse(): WaitLabel {
        require(isRequest)
        return WaitLabel(LabelKind.Response, mutex)
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // TODO: provide an option to enable spurious wake-ups
        // (isRequest && label is InitializationLabel) ->
        //     WaitLabel(LabelKind.Response, mutex)

        label is NotifyLabel && other is WaitLabel && other.isRequest
                && label.mutex == other.mutex ->
            other.getResponse()

        else -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when {
        label is WaitLabel && label.isRequest && other is WaitLabel && other.isResponse
                && label.mutex == other.mutex ->
            true

        label is NotifyLabel && other is WaitLabel && other.isResponse
                && label.mutex == other.mutex ->
            true

        // TODO: provide an option to enable spurious wake-ups
        // label is InitializationLabel -> true

        else -> false
    }

}

class ParkSynchronizationAlgebra : SynchronizationAlgebra {

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
        label is ParkLabel && label.isRequest && other is ParkLabel && other.isResponse
                && label.threadId == other.threadId ->
            true

        label is UnparkLabel && other is ParkLabel && other.isResponse
                && label.threadId == other.threadId ->
            true

        // TODO: provide an option to enable spurious wake-ups
        // label is InitializationLabel -> true

        else -> false
    }

}

class AtomicSynchronizationAlgebra : SynchronizationAlgebra {

    // TODO: generalize this construction to arbitrary list of disjoint algebras

    val threadForkAlgebra   = ThreadForkSynchronizationAlgebra()
    val threadJoinAlgebra   = ThreadJoinSynchronizationAlgebra()
    val memoryAccessAlgebra = MemoryAccessSynchronizationAlgebra()
    val lockAlgebra         = LockSynchronizationAlgebra()
    val waitAlgebra         = WaitSynchronizationAlgebra()
    val parkAlgebra         = ParkSynchronizationAlgebra()

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is ThreadStartLabel     -> threadForkAlgebra.syncType(label)
        is ThreadForkLabel      -> threadForkAlgebra.syncType(label)
        is ThreadJoinLabel      -> threadJoinAlgebra.syncType(label)
        is ThreadFinishLabel    -> threadJoinAlgebra.syncType(label)
        is MemoryAccessLabel    -> memoryAccessAlgebra.syncType(label)
        is LockLabel            -> lockAlgebra.syncType(label)
        is UnlockLabel          -> lockAlgebra.syncType(label)
        is WaitLabel            -> waitAlgebra.syncType(label)
        is NotifyLabel          -> waitAlgebra.syncType(label)
        is ParkingEventLabel    -> parkAlgebra.syncType(label)
        else                    -> null
    }

    override fun forwardSynchronize(label: EventLabel, other: EventLabel): EventLabel? = when (other) {
        is ThreadStartLabel     -> threadForkAlgebra.forwardSynchronize(label, other)
        is ThreadJoinLabel      -> threadJoinAlgebra.forwardSynchronize(label, other)
        is ThreadFinishLabel    -> threadJoinAlgebra.forwardSynchronize(label, other)
        is ReadAccessLabel      -> memoryAccessAlgebra.forwardSynchronize(label, other)
        is LockLabel            -> lockAlgebra.forwardSynchronize(label, other)
        is WaitLabel            -> waitAlgebra.forwardSynchronize(label, other)
        is ParkLabel            -> parkAlgebra.forwardSynchronize(label, other)
        else                    -> null
    }

    override fun synchronizesInto(label: EventLabel, other: EventLabel): Boolean = when (other) {
        is ThreadStartLabel     -> threadForkAlgebra.synchronizesInto(label, other)
        is ThreadJoinLabel      -> threadJoinAlgebra.synchronizesInto(label, other)
        is ThreadFinishLabel    -> threadJoinAlgebra.synchronizesInto(label, other)
        is ReadAccessLabel      -> memoryAccessAlgebra.synchronizesInto(label, other)
        is LockLabel            -> lockAlgebra.synchronizesInto(label, other)
        is WaitLabel            -> waitAlgebra.synchronizesInto(label, other)
        is ParkLabel            -> parkAlgebra.synchronizesInto(label, other)
        else                    -> false
    }

}