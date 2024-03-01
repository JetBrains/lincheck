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
 * Synchronization algebra describes how event labels can synchronize to form new labels.
 * For example, write access label can synchronize with read-request label to form a read-response label.
 * The response label takes the value written by the write access as its read value.
 *
 * When appropriate, we use notation `\+` to denote synchronization binary operation:
 *
 * ```
 *     Write(x, v) \+ Read^{req}(x) = Read^{rsp}(x, v)
 * ```
 *
 * Synchronize operation is expected to be associative
 * (and commutative in case of [CommutativeSynchronizationAlgebra]).
 * It is a partial operation --- some labels cannot participate in synchronization
 * (e.g., write access label cannot synchronize with another write access label).
 * In such cases, the synchronization operation returns null:
 *
 * ```
 *     Write(x, v) \+ Write(y, u) = null
 * ```
 *
 * In case when a pair of labels can synchronize, we also say that they are synchronizable.
 * Given a pair of synchronizable labels, we say that these labels synchronize into the synchronization result label.
 * We use notation `\>>` to denote the synchronize-into relation and `<</` to denote synchronized-from relation.
 * Therefore, if `A \+ B = C` then `A \>> C` and `B \>> C`.
 *
 * The [synchronize] method should implement synchronization operation.
 * It is not obligatory to override [synchronizable] method, which checks if a pair of labels is synchronization.
 * This is because the default implementation is guaranteed to be consistent with [synchronize]
 * (it just checks that result of [synchronize] is not null).
 * However, the overridden implementation can optimize this check.
 *
 * **Note**: formally, synchronization algebra is the special algebraic structure
 * deriving from partial commutative monoid [1].
 * The synchronizes-into relation corresponds to the irreflexive kernel of
 * the divisibility pre-order associated with the synchronization monoid.
 *
 * [[1]] "Event structure semantics for CCS and related languages."
 *   _Glynn Winskel._
 *   _International Colloquium on Automata, Languages, and Programming._
 *   _Springer, Berlin, Heidelberg, 1982._
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
     * @return label representing the result of synchronization
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

    /* TODO: make synchronization algebras cancellative and splittable PCM?
     *   With these properties we can define `split` function that returns
     *   a unique decomposition of any given label.
     *   Then we can derive implementation of `synchronizesInto` function.
     *   To do this we need to guarantee unique decomposition,
     *   currently it does not always hold
     *  (e.g. because of InitializationLabel synchronizing with ReadLabel).
     *   We need to apply some tricks to overcome this.
     */
}

/**
 * Synchronizes two nullable event labels.
 *
 * @return the label representing the result of synchronization,
 *   or null if the labels cannot synchronize, or one of the labels is null.
 */
fun SynchronizationAlgebra.synchronize(label: EventLabel?, other: EventLabel?): EventLabel? = when {
    label == null -> other
    other == null -> label
    else -> synchronize(label, other)
}

/**
 * Synchronizes a list of events using the provided synchronization algebra.
 *
 * @param events the list of events which labels need to be synchronized.
 * @return the label representing the result of synchronization,
 *   or null if event labels are not synchronizable, or the list of events is empty.
 */
fun SynchronizationAlgebra.synchronize(events: List<Event>): EventLabel? {
    if (events.isEmpty())
        return null
    return events.fold (null) { label: EventLabel?, event ->
        synchronize(label, event.label)
    }
}

/**
 * A commutative synchronization algebra is a synchronization algebra
 * whose [synchronize] operation is expected to be commutative.
 *
 * @see SynchronizationAlgebra
 */
interface CommutativeSynchronizationAlgebra : SynchronizationAlgebra

/**
 * Constructs commutative synchronization algebra derived from the non-commutative one
 * by trying to apply [synchronize] operation in both directions.
 */
fun CommutativeSynchronizationAlgebra(algebra: SynchronizationAlgebra) = object : CommutativeSynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? =
        algebra.syncType(label)

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? =
        algebra.synchronize(label, other) ?: algebra.synchronize(other, label)

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
 * - [SynchronizationType.Binary] binary synchronization --- only a pair of events can synchronize.
 *     For example, write access label can synchronize with read-request label,
 *     but the resulting read-response label can no longer synchronize with any other label.
 *
 * - [SynchronizationType.Barrier] barrier synchronization --- a set of events can synchronize.
 *     For example, several thread finish labels can synchronize with a single thread
 *     join-request label waiting for all of these threads to complete.
 *
 * @see [EventLabel]
 */
enum class SynchronizationType { Binary, Barrier }

/**
 * Thread synchronization algebra defines synchronization rules
 * for different types of thread event labels.
 *
 * The rules are as follows.
 *
 *   - Thread fork synchronizes with thread start
 *     if the thread id of the starting thread is in the set of forked threads:
 *
 *     ```
 *         TFork(ts) \+ TStart^req(t) = TStart^rsp(t) | if t in ts
 *     ```
 *
 *   - Thread finish synchronizes with thread join,
 *     wherein the set of finished thread ids is subtracted from the set of joined thread ids.
 *
 *     ```
 *         TFinish(ts) \+ TJoin^{req/rsp}(ts') = TJoin^rsp(ts' \ ts)
 *     ```
 *
 *   - Two thread finish labels synchronize, and their sets of finished thread ids are joined.
 *
 *     ```
 *         TFinish(ts) \+ TFinish(ts') = TFinish(ts + ts')
 *     ```
 */
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

}

/**
 * Checks whether this [ThreadStartLabel] is a valid response to the given [label].
 *
 * @see ThreadStartLabel.getResponse
 */
fun ThreadStartLabel.isValidResponse(label: EventLabel): Boolean {
    require(isResponse)
    return when (label) {
        is ThreadStartLabel ->
            label.isRequest && threadId == label.threadId
        else ->
            label.asThreadForkLabel()
                ?.let { threadId in it.forkThreadIds } ?: false
    }
}

/**
 * Returns the response label for this [ThreadStartLabel]
 * given [label] as a potential sender event label to respond to.
 *
 * @param label the label to respond to.
 * @return the response label if it is valid, null otherwise.
 */
fun ThreadStartLabel.getResponse(label: EventLabel): ThreadStartLabel? = when {
    isRequest -> label.asThreadForkLabel()
        ?.takeIf { isRequest && threadId in it.forkThreadIds }
        ?.let { this.copy(kind = LabelKind.Response) }

    else -> null
}

/**
 * Checks whether this [ThreadJoinLabel] is a valid response to the given [label].
 *
 * @see ThreadJoinLabel.getResponse
 */
fun ThreadJoinLabel.isValidResponse(label: EventLabel): Boolean {
    require(isResponse)
    return when (label) {
        is ThreadJoinLabel ->
            label.joinThreadIds.containsAll(joinThreadIds)
        is ThreadFinishLabel ->
            label.finishedThreadIds.all { it !in joinThreadIds }
        else -> false
    }
}

/**
 * Returns the response label for this [ThreadJoinLabel]
 * given [label] as a potential sender event label to respond to.
 *
 * @param label the label to respond to.
 * @return the response label if it is valid, null otherwise.
 */
fun ThreadJoinLabel.getResponse(label: EventLabel): EventLabel? = when {
    !isReceive && label is ThreadFinishLabel && joinThreadIds.containsAll(label.finishedThreadIds) ->
        this.copy(
            kind = LabelKind.Response,
            joinThreadIds = joinThreadIds - label.finishedThreadIds
        )

    else -> null
}

/**
 * Joins this [ThreadFinishLabel] with the given [label].
 * Defined only if the [label] is also a [ThreadFinishLabel], otherwise returns null.
 */
fun ThreadFinishLabel.join(label: EventLabel): ThreadFinishLabel? = when {
    (label is ThreadFinishLabel) ->
        this.copy(finishedThreadIds = finishedThreadIds + label.finishedThreadIds)

    else -> null
}

/**
 * Memory access synchronization algebra defines synchronization rules
 * for different types of memory access event labels.
 *
 * The rules are as follows.
 *
 *   - Write access synchronizes with read access from the same memory location,
 *     passing its value into it:
 *
 *     ```
 *         Write(x, v) \+ Read^req(x) = Read^rsp(x, v)
 *     ```
 */

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

}

/**
 * Checks whether this [ReadAccessLabel] is a valid response to the given [label].
 *
 * @see ReadAccessLabel.getResponse
 */
fun ReadAccessLabel.isValidResponse(label: EventLabel): Boolean {
    require(isResponse)
    return when {
        label is ReadAccessLabel && label.isRequest ->
            kClass == label.kClass &&
            location == label.location &&
            isExclusive == label.isExclusive

        else -> label.asWriteAccessLabel(location)?.let {
            // TODO: also check kClass
            value == it.value
        } ?: false
    }
}

/**
 * Returns the response label for this [ReadAccessLabel]
 * given [label] as a potential sender event label to respond to.
 *
 * @param label the label to respond to.
 * @return the response label if it is valid, null otherwise.
 */
fun ReadAccessLabel.getResponse(label: EventLabel): EventLabel? = when {
    isRequest -> label.asWriteAccessLabel(location)?.let { write ->
        // TODO: perform dynamic type-check
        this.copy(
            kind = LabelKind.Response,
            value = write.value,
        )
    }

    else -> null
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

}

val AtomicSynchronizationAlgebra = CommutativeSynchronizationAlgebra(object : SynchronizationAlgebra {

    // TODO: generalize this construction to arbitrary list of disjoint algebras (?)

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is ThreadEventLabel         -> ThreadSynchronizationAlgebra.syncType(label)
        is MemoryAccessLabel        -> MemoryAccessSynchronizationAlgebra.syncType(label)
        is MutexLabel               -> MutexSynchronizationAlgebra.syncType(label)
        is ParkingEventLabel        -> ParkingSynchronizationAlgebra.syncType(label)
        is CoroutineLabel           -> CoroutineSynchronizationAlgebra.syncType(label)

        // special treatment of ObjectAllocationLabel,
        // because it can contribute to several sub-algebras
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

})

private val ReceiveAggregationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? =
        if (label.isRequest || label.isResponse) SynchronizationType.Binary else null

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        label.isRequest && other.isResponse && other.isValidResponse(label) ->
            other.getReceive()
        else -> null
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
        is MutexLabel           -> MutexAggregationAlgebra.synchronize(label, other)
        is ActorLabel           -> null
        else                    -> ReceiveAggregationAlgebra.synchronize(label, other)
    }

}
