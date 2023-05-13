/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.implies
import java.util.WeakHashMap
import kotlin.reflect.KClass

/**
 * EventLabel is a base class for the hierarchy of classes representing semantic labels
 * of atomic events performed by the program.
 * It includes events such as:
 * - thread fork, join, start and finish;
 * - reads and writes from/to shared memory;
 * - lock acquisitions and releases;
 * and other (see subclasses of this class).
 *
 * Label can be one of the following kind (see [LabelKind]):
 * - [LabelKind.Send] --- send label.
 * - [LabelKind.Request] --- request label.
 * - [LabelKind.Response] --- response label.
 * For example, giving [MemoryAccessLabel],
 * [WriteAccessLabel] is an example of the send label,
 * while [ReadAccessLabel] is split into request and response part.
 *
 * Labels can synchronize to form new labels using [synchronize] method.
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
 * synchronization (e.g. write access label cannot synchronize with lock acquire request).
 * In such cases [synchronize] returns null.
 *
 * ```
 * Write(x, v) \+ Lock^{req}(l) = null
 * ```
 *
 * In case when a pair of labels can synchronize we also say that they synchronize-with each other
 * and use notation `<+>` to denote this relation.
 * Do not confuse this synchronizes-with relation with the synchronizes-with relation from the Java Memory Model.
 * Method [synchronizesWith] checks whether two labels can synchronize.
 *
 * Given a pair of synchronizable labels, we say that
 * these labels synchronize-into the synchronization result label.
 * Equivalently, we say that the result label is synchronized-from the argument labels.
 * Methods [synchronizesInto] and [synchronizedFrom] implement these relations.
 * We use notation `\>>` to denote the synchronize-into relation and `<</` to denote synchronized-from relation.
 * Therefore, if `A \+ B = C` then `A \>> C` and `B \>> C`.
 *
 * It is responsibility of [EventLabel] subclasses to override [synchronize] method
 * in case when they need to implement non-trivial synchronization.
 * Default implementation only checks for synchronization against
 * special dummy [EmptyLabel] that behaves as a neutral element of [synchronize] operation.
 * Every label should be able to synchronize with [EmptyLabel] and produce itself.
 *
 * ```
 * Write(x, v) \+ Empty = Write(x, v)
 * ```
 *
 * In the case of non-trivial synchronization it is also necessary to override [synchronizedFrom] method,
 * because its implementation should be consistent with [synchronize]
 * ([synchronizesInto] method implementation is derived from [synchronizedFrom]).
 * It is not obligatory to override [synchronizesWith] method, because the default implementation
 * is guaranteed to be consistent with [synchronize] (it just checks that result of [synchronize] is not null).
 * However, the overridden implementation can optimize this check.
 *
 * Formally, labels form a synchronization algebra [1],
 * that is the algebraic structure (deriving from partial commutative monoid)
 * defining how individual atomic events can synchronize to produce new events.
 * The synchronizes-into relation corresponds to the irreflexive kernel of
 * the divisibility pre-order associated with the synchronization monoid.
 *
 * [1] Winskel, Glynn. "Event structure semantics for CCS and related languages."
 *     International Colloquium on Automata, Languages, and Programming. Springer, Berlin, Heidelberg, 1982.
 *
 * @param kind the kind of this label: send, request or response.
 * @param syncType the synchronization type of this label: binary or barrier.
 * @param isBlocking whether this label is blocking.
 *    Load acquire-request and thread join-request/response are examples of blocking labels.
 * @param unblocked whether this blocking label is already unblocked.
 *   For example, thread join-response is unblocked when all the threads it waits for have finished.
 */
abstract class EventLabel(
    open val kind: LabelKind,
    val syncType: SynchronizationType,
    val isBlocking: Boolean = false,
    val unblocked: Boolean = true,
) {
    /**
     * Synchronizes event label with another label passed as a parameter.
     * Default implementation provides synchronization only with [EmptyLabel].
     *
     * @param label label to synchronize with.
     * @return label representing result of synchronization
     *   or null if this label cannot synchronize with [label].
     * @see EventLabel
     */
    open infix fun synchronize(label: EventLabel): EventLabel? =
        if (label is EmptyLabel) this else null

    /**
     * Checks whether two labels can synchronize.
     * Default implementation just checks that result of [synchronize] is not null,
     * overridden implementation can optimize this check.
     *
     * @see EventLabel
     */
    open infix fun synchronizesWith(label: EventLabel): Boolean =
        (synchronize(label) != null)

    /**
     * Checks whether this label synchronizes-into the argument label,
     * i.e. there exists another label which can be synchronized with this label to produce argument label.
     * Inverse of [synchronizedFrom].
     *
     * @see synchronizedFrom
     */
    fun synchronizesInto(label: EventLabel): Boolean =
        label.synchronizedFrom(this)

    /**
     * Checks whether this label synchronizes-from the argument label,
     * i.e. it is a result of synchronization of the argument label with some another label.
     * Inverse of [synchronizesInto].
     *
     * Default implementation returns true only if [label] is [EmptyLabel],
     * since by default label can synchronize only with [EmptyLabel]
     * (due to default implementation of [synchronize]).
     * If subclass overrides [synchronize] it should also override this method.
     *
     * Flag [relaxedCheck] can be passed to relax the synchronizes-from check
     * in presence of partially replayed execution (see [replay]).
     *
     * @see EventLabel
     */
    open fun synchronizedFrom(label: EventLabel): Boolean =
        (label is EmptyLabel)

    /**
     * Checks whether this label is send label.
     */
    val isSend: Boolean
        get() = (kind == LabelKind.Send)

    /**
     * Checks whether this label is request label.
     */
    val isRequest: Boolean
        get() = (kind == LabelKind.Request)

    /**
     * Checks whether this label is response label.
     */
    val isResponse: Boolean
        get() = (kind == LabelKind.Response)

    /**
     * Checks whether this label has binary synchronization.
     */
    val isBinarySynchronizing: Boolean
        get() = (syncType == SynchronizationType.Binary)

    /**
     * Checks whether this label has barrier synchronization.
     */
    val isBarrierSynchronizing: Boolean
        get() = (syncType == SynchronizationType.Barrier)

    /**
     * Recipient object for the operation represented by the label.
     * For example, for object field memory access labels this is the accessed object.
     * If particular subclass of labels does not have natural recipient object
     * then this property is null.
     */
    open val recipient: Any? = null

    /**
     * An index of a label which is used to group semantically similar and
     * potentially concurrent labels operating on the same object or location.
     * For example, for object field memory access labels this is the accessed memory location.
     * Note that [index] and [recipient] do not necessarily match. In case of object field memory access labels,
     * the [index] is a tuple of accessed object, accessed field name and class name,
     * while [recipient] is only the accessed object itself.
     * If particular subclass of labels does not have natural index
     * then this property is null.
     */
    open val index: Any? = null

    /**
     * Replays this label using another label given as argument.
     *
     * Replaying can be used by an executor in order to reproduce execution saved as a list of labels.
     * Because some values of execution can change non-deterministically
     * between different invocations (for example, addresses of allocated objects),
     * replaying execution scenario may require to change some internal state
     * kept in event label, without modifying shape of the label.
     * For example, write access label should remain write access label,
     * but the address of the accessed memory location can change.
     *
     * This method takes as arguments [label] to replay and current [remapping]
     * that keeps the mapping from old to new objects.
     * If the shape of this label matches the shape of argument [label], then
     * internal state of this label is changed to match the state of [label],
     * and the [remapping] is updated to reflect this change.
     * If [remapping] already has conflicting information
     * (for example, it maps address of allocated object to another address not
     * equal to the address stored in [label]), then the exception is thrown.
     *
     * @param label to replay this label.
     * @param remapping stores the mapping from old to new replayed objects.
     * @throws IllegalStateException if shapes of labels do not match or
     *   [remapping] already contains a binding that contradicts the replaying.
     */
    open fun replay(label: EventLabel, remapping: Remapping) {
        check(this == label) {
            "Event label $this cannot be replayed by $label"
        }
    }

    /**
     * Changes the internal state of this label using given [remapping].
     *
     * @see replay
     */
    open fun remap(remapping: Remapping) {}

    fun remapRecipient(label: EventLabel, remapping: Remapping = Remapping()) {
        // TODO: check that labels have same operation kind
        remapping[recipient] = label.recipient
        remap(remapping)
    }
}

/**
 * Kind of label: send, request or response.
 *
 * @see EventLabel
 */
enum class LabelKind { Send, Request, Response }

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

/**
 * Dummy empty label acting as a neutral element of [synchronize] operation.
 *
 * For each label `L` it should be true that:
 *
 * ```
 * L \+ Empty = Empty \+ L = L
 * ```
 */
class EmptyLabel : EventLabel(LabelKind.Send, SynchronizationType.Binary) {

    override fun synchronize(label: EventLabel) = label

    override fun synchronizedFrom(label: EventLabel): Boolean = false

    override fun toString(): String = "Empty"

}

/**
 * Special label acting as a label of the virtual root event of every execution.
 *
 * Has [LabelKind.Send] kind.
 *
 * Initialization label can synchronize with various different labels.
 * Usually synchronization of initialization label with some request label
 * results in response label that obtains some default value.
 * For example, synchronizing initialization label with read-request label of integer
 * results in read-response label with 0 value.
 *
 * ```
 * Init \+ Read^{req}(x) = Read^{rsp}(x, 0)
 * ```
 */
class InitializationLabel(
    val memoryInitializer: MemoryInitializer
) : EventLabel(LabelKind.Send, SynchronizationType.Binary) {
    // TODO: can barrier-synchronizing events also utilize InitializationLabel?

    private val initialValues = WeakHashMap<MemoryLocation, OpaqueValue>()

    val initializedMemoryLocations: Set<MemoryLocation> =
        initialValues.keys

    fun initialValue(location: MemoryLocation): OpaqueValue? =
        initialValues
            .computeIfAbsent(location) { memoryInitializer(it) ?: NULL }
            .takeIf { it != NULL }

    fun asWriteLabel(location: MemoryLocation) = WriteAccessLabel(
        location_ = location,
        value_ = initialValues[location],
        isExclusive = false,
    )

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is InitializationLabel) null else label.synchronize(this)

    override fun toString(): String = "Init"

    companion object {
        private val NULL : OpaqueValue = Any().opaque()
    }

}

/**
 * Base class for all thread event labels.
 *
 * @param kind the kind of this label.
 * @param syncType the synchronization type of this label.
 * @param isBlocking whether this label is blocking.
 * @param unblocked whether this blocking label is already unblocked.
  */
abstract class ThreadEventLabel(
    kind: LabelKind,
    syncType: SynchronizationType,
    isBlocking: Boolean = false,
    unblocked: Boolean = true,
): EventLabel(kind, syncType, isBlocking, unblocked)

/**
 * Label representing fork of a set of threads.
 *
 * Has [LabelKind.Send] kind.
 *
 * Can synchronize with [ThreadStartLabel].
 *
 * @param forkThreadIds a set of thread ids this fork spawns.
 */
data class ThreadForkLabel(
    val forkThreadIds: Set<Int>,
): ThreadEventLabel(
    kind = LabelKind.Send,
    syncType = SynchronizationType.Binary,
) {

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is ThreadStartLabel)
            label.synchronize(this)
        else super.synchronize(label)

    override fun toString(): String =
        "ThreadFork(${forkThreadIds})"

}

/**
 * Label of virtual event put into beginning of each thread.
 *
 * Can either be of [LabelKind.Request] or response [LabelKind.Response] kind.
 *
 * Thread start-request label can synchronize with [InitializationLabel] (for main thread)
 * or with [ThreadForkLabel] to produce thread start-response.
 *
 * @param kind the kind of this label: [LabelKind.Request] or [LabelKind.Response]..
 * @param threadId thread id of started thread.
 * @param isMainThread whether this is the main thread,
 *   i.e. the thread starting the execution of the whole program.
 */
data class ThreadStartLabel(
    override val kind: LabelKind,
    val threadId: Int,
    val isMainThread: Boolean = false,
): ThreadEventLabel(kind, SynchronizationType.Binary) {

    init {
        check(isRequest || isResponse)
    }

    override fun synchronize(label: EventLabel): EventLabel? = when {
        isRequest && isMainThread && label is InitializationLabel -> {
            ThreadStartLabel(
                threadId = threadId,
                kind = LabelKind.Response,
                isMainThread = true,
            )
        }

        isRequest && label is ThreadForkLabel && threadId in label.forkThreadIds -> {
            ThreadStartLabel(
                threadId = threadId,
                kind = LabelKind.Response,
                isMainThread = false,
            )
        }

        else -> super.synchronize(label)
    }

    override fun synchronizedFrom(label: EventLabel): Boolean = when {
        !isResponse -> false
        label is ThreadStartLabel && label.isRequest && threadId == label.threadId -> true
        label is ThreadForkLabel && !isMainThread && threadId in label.forkThreadIds -> true
        label is InitializationLabel && isMainThread -> true
        else -> false
    }

    override fun toString(): String = "ThreadStart"

    // TODO: should we override `recipient` of `ThreadStartLabel` to be the thread id of the starting thread?
}

/**
 * Label of a virtual event put into the end of each thread.
 *
 * Has [LabelKind.Send] kind.
 *
 * Can synchronize with [ThreadJoinLabel] or another [ThreadFinishLabel].
 * In the latter case the sets of [finishedThreadIds] of two labels are merged in the resulting label.
 *
 * Thread finish label is considered to be always blocked.
 *
 * @param finishedThreadIds set of threads that have been finished.
 */
data class ThreadFinishLabel(
    val finishedThreadIds: Set<Int>
): ThreadEventLabel(
    kind = LabelKind.Send,
    syncType = SynchronizationType.Barrier,
    isBlocking = true,
    unblocked = false,
) {

    constructor(threadId: Int): this(setOf(threadId))

    override fun synchronize(label: EventLabel): EventLabel? = when {
        // TODO: handle cases of invalid synchronization:
        //  - when there are multiple ThreadFinish labels with the same thread id
        //  - when thread finishes outside of matching ThreadFork/ThreadJoin scope
        //  In order to handle the last case we need to add `scope` parameter to Thread labels?.
        //  Throw `InvalidBarrierSynchronizationException` in these cases.
        (label is ThreadJoinLabel && label.joinThreadIds.containsAll(finishedThreadIds)) -> {
            ThreadJoinLabel(
                kind = LabelKind.Response,
                joinThreadIds = label.joinThreadIds - finishedThreadIds,
            )
        }

        (label is ThreadFinishLabel) -> {
            ThreadFinishLabel(
                finishedThreadIds = finishedThreadIds + label.finishedThreadIds
            )
        }

        else -> super.synchronize(label)
    }

    override fun synchronizedFrom(label: EventLabel): Boolean =
        label is ThreadFinishLabel && finishedThreadIds.containsAll(label.finishedThreadIds)

    override fun toString(): String = "ThreadFinish"

}

/**
 * Label representing join of a set of threads.
 *
 * Can either be of [LabelKind.Request] or response [LabelKind.Response] kind.
 * Both thread join-request and join-response labels can synchronize with
 * [ThreadFinishLabel] to produce another thread join-response label,
 * however the new join label no longer waits for synchronized finished thread.
 *
 * This is blocking label, it becomes [unblocked] when set of
 * wait-to-be-join threads becomes empty (see [joinThreadIds]).
 *
 * @param kind the kind of this label: [LabelKind.Request] or [LabelKind.Response].
 * @param joinThreadIds set of threads this label awaits to join.
 */
data class ThreadJoinLabel(
    override val kind: LabelKind,
    val joinThreadIds: Set<Int>,
): ThreadEventLabel(
    kind = kind,
    syncType = SynchronizationType.Barrier,
    isBlocking = true,
    unblocked = (kind == LabelKind.Response) implies joinThreadIds.isEmpty(),
) {

    init {
        check(isRequest || isResponse)
    }

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is ThreadFinishLabel)
            label.synchronize(this)
        else super.synchronize(label)

    override fun synchronizedFrom(label: EventLabel): Boolean = when {
        !isResponse -> false
        label is ThreadJoinLabel && label.isRequest && label.joinThreadIds.containsAll(joinThreadIds) -> true
        label is ThreadFinishLabel && label.finishedThreadIds.all { it !in joinThreadIds } -> true
        else -> false
    }


    override fun toString(): String =
        "ThreadJoin(${joinThreadIds})"
}

/**
 * Base class of read and write shared memory access event labels.
 * Stores common information about memory access ---
 * such as accessing memory location and read or written value.
 *
 * @param kind the kind of this label.
 * @param location_ memory location affected by this memory access.
 * @param value_ written value for write access, read value for read access.
 * @param kClass class of written or read value.
 * @param isExclusive flag indicating whether this access is exclusive.
 *   Memory accesses obtained as a result of executing atomic read-modify-write
 *   instructions (such as CAS) have this flag set.
 */
sealed class MemoryAccessLabel(
    kind: LabelKind,
    protected open var location_: MemoryLocation,
    protected open var value_: OpaqueValue?,
    open val kClass: KClass<*>,
    open val isExclusive: Boolean = false
): EventLabel(kind, SynchronizationType.Binary) {

    /**
     * Memory location affected by this memory access.
     */
    val location: MemoryLocation
        get() = location_

    /**
     * Written value for write access, read value for read access.
     */
    val value: OpaqueValue?
        get() = value_

    /**
     * Kind of memory access of this label:
     * either [MemoryAccessKind.Write] or [MemoryAccessKind.Read].
     */
    val accessKind: MemoryAccessKind
        get() = when(this) {
            is WriteAccessLabel -> MemoryAccessKind.Write
            is ReadAccessLabel -> MemoryAccessKind.Read
        }

    /**
     * Checks whether this memory access is write.
     */
    val isWrite: Boolean
        get() = (accessKind == MemoryAccessKind.Write)

    /**
     * Checks whether this memory access is read.
     */
    val isRead: Boolean
        get() = (this is ReadAccessLabel)

    /**
     * Recipient of a memory access label is equal to its memory location's recipient.
     */
    override val recipient: Any?
        get() = location.recipient

    /**
     * Index of a memory access label is equal to accessed memory location.
     */
    override val index: Any?
        get() = location

    /**
     * Replays this memory access label using another memory access label given as argument.
     * Replaying can substitute accessed memory location and read/written value of the memory access.
     *
     * @see EventLabel.replay
     */
    override fun replay(label: EventLabel, remapping: Remapping) {
        check(label is MemoryAccessLabel &&
            kind == label.kind &&
            accessKind == label.accessKind &&
            kClass == label.kClass &&
            isExclusive == label.isExclusive) {
            "Event label $this cannot be replayed by $label"
        }
        location.replay(label.location, remapping)
        // TODO: check primitive values are equal
        remapping[value?.unwrap()] = label.value?.unwrap()
        value_ = label.value
    }

    /**
     * Remaps memory location and value according to given [remapping].
     *
     * @see EventLabel.replay
     */
    override fun remap(remapping: Remapping) {
        location.remap(remapping)
        value?.unwrap()
            ?.let { remapping[it] }
            ?.also { value_ = it.opaque() }
    }

    override fun toString(): String {
        val kindString = when (kind) {
            LabelKind.Send -> ""
            LabelKind.Request -> "^req"
            LabelKind.Response -> "^rsp"
        }
        val exclString = if (isExclusive) "_ex" else ""
        val argsString = "$location" + if (kind != LabelKind.Request) ", $value" else ""
        return "${accessKind}${kindString}${exclString}(${argsString})"
    }

}

/**
 * Kind of memory access.
 *
 * @see MemoryAccessLabel
 */
enum class MemoryAccessKind { Read, Write }

/**
 * Label denoting read access to shared memory.
 *
 * Can either be of [LabelKind.Request] or [LabelKind.Response] kind.
 * Read-request can synchronize either with [InitializationLabel] or with [WriteAccessLabel]
 * to produce read-response label. In the former case response will take default value
 * for given class, provided by [kClass] constructor argument.
 * In the latter case read-response will take value of the write label.
 *
 * @param kind the kind of this label: [LabelKind.Request] or [LabelKind.Response] kind.
 * @param location_ memory location of this read.
 * @param value_ the read value, for read-request label equals to null.
 * @param kClass the class of read value.
 * @param isExclusive exclusive access flag.
 *
 * @see MemoryAccessLabel
 */
data class ReadAccessLabel(
    override val kind: LabelKind,
    override var location_: MemoryLocation,
    override var value_: OpaqueValue?,
    override val kClass: KClass<*>,
    override val isExclusive: Boolean = false
): MemoryAccessLabel(kind, location_, value_, kClass, isExclusive) {

    init {
        require(isRequest || isResponse)
        require(isRequest implies (value == null))
    }

    override fun synchronize(label: EventLabel): EventLabel? = when {
        (isRequest && label is WriteAccessLabel && location == label.location) ->
            completeRequest(label.value)

        (isRequest && label is InitializationLabel) ->
            completeRequest(label.initialValue(location))

        else -> super.synchronize(label)
    }

    private fun completeRequest(value: OpaqueValue?): ReadAccessLabel {
        require(isRequest)
        // TODO: perform dynamic type-check
        // require(value.isInstanceOf(kClass))
        return ReadAccessLabel(
            kind = LabelKind.Response,
            location_ = location,
            value_ = value,
            kClass = kClass,
            isExclusive = isExclusive,
        )
    }

    override fun synchronizedFrom(label: EventLabel): Boolean = when {
        !isResponse -> false

        label is ReadAccessLabel && label.isRequest &&
            kClass == label.kClass &&
            isExclusive == label.isExclusive &&
            location == label.location
        -> true

        label is WriteAccessLabel &&
            location == label.location &&
            // TODO: also check kClass
            value == label.value
        -> true

        label is InitializationLabel &&
            value == label.initialValue(location)
        -> true

        else -> false
    }

    override fun toString(): String = super.toString()

}

/**
 * Label denoting write access to shared memory.
 *
 * Has [LabelKind.Send] kind.
 * Can synchronize with request [ReadAccessLabel] producing read-response label.
 *
 * @param location_ the memory location affected by this write.
 * @param value_ the written value.
 * @param kClass the class of written value.
 * @param isExclusive exclusive access flag.
 *
 * @see MemoryAccessLabel
 */
data class WriteAccessLabel(
    override var location_: MemoryLocation,
    override var value_: OpaqueValue?,
    override val kClass: KClass<*> = value_!!.unwrap()::class,
    override val isExclusive: Boolean = false
): MemoryAccessLabel(LabelKind.Send, location_, value_, kClass, isExclusive) {

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is ReadAccessLabel)
            label.synchronize(this)
        else super.synchronize(label)

    override fun toString(): String = super.toString()

}

/**
 * Base class of all mutex operations event labels.
 *
 * @param kind the kind of this label.
 * @param mutex_ the mutex to perform operation on.
 * @param isBlocking whether this label is blocking.
 * @param unblocked whether this blocking label is already unblocked.
 */
sealed class MutexLabel(
    kind: LabelKind,
    protected open var mutex_: OpaqueValue,
    isBlocking: Boolean = false,
    unblocked: Boolean = true,
): EventLabel(kind, SynchronizationType.Binary, isBlocking, unblocked) {

    /**
     * Lock object affected by this operation.
     */
    val mutex: OpaqueValue
        get() = mutex_

    /**
     * Kind of mutex operation.
     */
    val operationKind: MutexOperationKind
        get() = when(this) {
            is LockLabel    -> MutexOperationKind.Lock
            is UnlockLabel  -> MutexOperationKind.Unlock
            is WaitLabel    -> MutexOperationKind.Wait
            is NotifyLabel  -> MutexOperationKind.Notify
        }

    /**
     * Recipient of a mutex label is its mutex object.
     */
    override val recipient: Any?
        get() = mutex

    /**
     * Index of a mutex label is the mutex object itself.
     */
    override val index: Any?
        // TODO: get() = mutex
        get() = null

    /**
     * Replays this mutex label using another mutex label given as argument.
     * Replaying can substitute accessed mutex object.
     *
     * @see EventLabel.replay
     */
    override fun replay(label: EventLabel, remapping: Remapping) {
        check(label is MutexLabel &&
            kind == label.kind &&
            operationKind == label.operationKind &&
            when (this) {
                is LockLabel -> label is LockLabel &&
                    reentranceDepth == label.reentranceDepth && reentranceCount == label.reentranceCount
                is UnlockLabel -> label is UnlockLabel &&
                    reentranceDepth == label.reentranceDepth && reentranceCount == label.reentranceCount
                is NotifyLabel -> label is NotifyLabel &&
                    isBroadcast == label.isBroadcast
                else -> true
            }) {
            "Event label $this cannot be replayed by $label"
        }
        remapping[mutex.unwrap()] = label.mutex.unwrap()
        mutex_ = label.mutex
    }

    override fun remap(remapping: Remapping) {
        remapping[mutex.unwrap()]?.also { mutex_ = it.opaque() }
    }

    override fun toString(): String {
        val kindString = when (kind) {
            LabelKind.Send -> ""
            LabelKind.Request -> "^req"
            LabelKind.Response -> "^rsp"
        }
        return "${operationKind}${kindString}($mutex)"
    }

}

/**
 * Kind of mutex operation.
 *
 * @see MutexLabel
 */
enum class MutexOperationKind { Lock, Unlock, Wait, Notify }

/**
 * Label denoting lock of a mutex.
 *
 * Can either be of [LabelKind.Request] or [LabelKind.Response] kind.
 *
 * Lock-request can synchronize either with [InitializationLabel] or with [UnlockLabel]
 * to produce lock-response label.
 *
 * @param kind the kind of this label: [LabelKind.Request] or [LabelKind.Response].
 * @param mutex_ the locked mutex.
 *
 * @see MutexLabel
 */
data class LockLabel(
    override val kind: LabelKind,
    override var mutex_: OpaqueValue,
    val reentranceDepth: Int = 1,
    val reentranceCount: Int = 1,
) : MutexLabel(
    kind = kind,
    mutex_ = mutex_,
    isBlocking = true,
    unblocked = (kind == LabelKind.Response),
) {
    init {
        require(isRequest || isResponse)
        require(reentranceDepth - reentranceCount >= 0)
        // TODO: checks for non-reentrant locks
    }

    val isReentry: Boolean =
        (reentranceDepth - reentranceCount > 0)

    private fun completeRequest(): LockLabel {
        require(isRequest)
        return LockLabel(
            kind = LabelKind.Response,
            mutex_ = mutex,
            reentranceDepth = reentranceDepth,
            reentranceCount = reentranceCount,
        )
    }

    override fun synchronize(label: EventLabel): EventLabel? = when {
        (isRequest && !isReentry && label is UnlockLabel && !label.isReentry && mutex == label.mutex) ->
            completeRequest()

        (isRequest && label is InitializationLabel) ->
            completeRequest()

        else -> super.synchronize(label)
    }

    override fun synchronizedFrom(label: EventLabel): Boolean = when {
        !isResponse -> false
        label is LockLabel && label.isRequest && mutex == label.mutex -> true
        label is UnlockLabel && !label.isReentry && !isReentry && mutex == label.mutex -> true
        label is InitializationLabel -> true
        else -> false
    }

    override fun toString(): String = super.toString()
}

/**
 * Label denoting unlock of a mutex.
 *
 * Has [LabelKind.Send] kind.
 *
 * Can synchronize with request [LockLabel] producing lock-response label.
 *
 * @param mutex_ the unblocked mutex.
 *
 * @see MutexLabel
 */
data class UnlockLabel(
    override var mutex_: OpaqueValue,
    val reentranceDepth: Int = 1,
    val reentranceCount: Int = 1,
) : MutexLabel(LabelKind.Send, mutex_) {

    init {
        // TODO: checks for non-reentrant locks
        require(reentranceDepth - reentranceCount >= 0)
    }

    val isReentry: Boolean =
        (reentranceDepth - reentranceCount > 0)

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is LockLabel)
            label.synchronize(this)
        else super.synchronize(label)

    override fun toString(): String = super.toString()
}

/**
 * Label denoting wait on a mutex.
 *
 * Can either be of [LabelKind.Request] or [LabelKind.Response] kind.
 *
 * Wait-request can synchronize either with [InitializationLabel] or with [NotifyLabel]
 * to produce wait-response label. The former case models spurious wake-ups (disabled currently).
 *
 * @param kind the kind of this label: [LabelKind.Request] or [LabelKind.Response].
 * @param mutex_ the mutex to wait on.
 *
 * @see MutexLabel
 */
data class WaitLabel(
    override val kind: LabelKind,
    override var mutex_: OpaqueValue,
) : MutexLabel(
    kind = kind,
    mutex_ = mutex_,
    isBlocking = true,
    unblocked = (kind == LabelKind.Response),
) {
    override fun synchronize(label: EventLabel): EventLabel? = when {
        // TODO: provide an option to enable spurious wake-ups
        // (isRequest && label is InitializationLabel) ->
        //     WaitLabel(LabelKind.Response, mutex)

        (isRequest && label is NotifyLabel && mutex == label.mutex) ->
            WaitLabel(LabelKind.Response, mutex)

        else -> super.synchronize(label)
    }

    override fun synchronizedFrom(label: EventLabel): Boolean = when {
        !isResponse -> false
        label is WaitLabel && label.isRequest && mutex == label.mutex -> true
        label is NotifyLabel && mutex == label.mutex -> true
        // TODO: provide an option to enable spurious wake-ups
        // label is InitializationLabel -> true
        else -> false
    }

    override fun toString(): String = super.toString()
}

/**
 * Label denoting notification of a mutex.
 *
 * Has [LabelKind.Send] kind.
 *
 * Can synchronize with [WaitLabel] request producing [WaitLabel] response.
 *
 * @param mutex_ notified mutex.
 * @param isBroadcast flag indication that this notification is broadcast,
 *   e.g. caused by notifyAll() method call.
 *
 * @see MutexLabel
 */
data class NotifyLabel(
    override var mutex_: OpaqueValue,
    val isBroadcast: Boolean
) : MutexLabel(LabelKind.Send, mutex_) {

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is WaitLabel)
            label.synchronize(this)
        else super.synchronize(label)

    override fun toString(): String = super.toString()
}


/**
 * Base class for park and unpark event labels.
 *
 * @param kind the kind of this label.
 * @param threadId identifier of parked or unparked thread.
 * @param isBlocking whether this label is blocking.
 * @param unblocked whether this blocking label is already unblocked.
 */
sealed class ParkingEventLabel(
    kind: LabelKind,
    open val threadId: Int,
    isBlocking: Boolean = false,
    unblocked: Boolean = true,
): EventLabel(kind, SynchronizationType.Binary, isBlocking, unblocked) {

    /**
     * Kind of mutex operation.
     */
    val operationKind: ParkingOperationKind
        get() = when(this) {
            is ParkLabel    -> ParkingOperationKind.Park
            is UnparkLabel  -> ParkingOperationKind.Unpark
        }

    override fun toString(): String {
        val kindString = when (kind) {
            LabelKind.Send -> ""
            LabelKind.Request -> "^req"
            LabelKind.Response -> "^rsp"
        }
        val argsString = if (operationKind == ParkingOperationKind.Unpark) "($threadId)" else ""
        return "${operationKind}${kindString}${argsString}"
    }

    // TODO: should we override `recipient` of `ParkingLabel` to be the thread id of the parked/unparked thread?
    // TODO: should we override `index` of `ParkingLabel` to be the thread id of the parked/unparked thread?
}

/**
 * Kind of parking operation.
 *
 * @see ParkingEventLabel
 */
enum class ParkingOperationKind { Park, Unpark }

/**
 * Label denoting park of a thread.
 *
 * Can either be of [LabelKind.Request] or [LabelKind.Response] kind.
 *
 * Park-request can synchronize either with [InitializationLabel] or with [UnparkLabel]
 * to produce park-response label. The former case models spurious wake-ups (disabled currently).
 *
 * @param kind the kind of this label: [LabelKind.Request] or [LabelKind.Response].
 * @param threadId the identifier of parked thread.
 *
 * @see ParkingEventLabel
 */
data class ParkLabel(
    override val kind: LabelKind,
    override val threadId: Int,
) : ParkingEventLabel(
    kind = kind,
    threadId = threadId,
    isBlocking = true,
    unblocked = (kind == LabelKind.Response),
) {
    init {
        require(isRequest || isResponse)
    }

    override fun synchronize(label: EventLabel): EventLabel? = when {
        (isRequest && label is UnparkLabel && threadId == label.threadId) ->
            ParkLabel(LabelKind.Response, threadId)

        // TODO: provide an option to enable spurious wake-ups
        // (isRequest && label is InitializationLabel) ->
        //     ParkLabel(LabelKind.Response, threadId)

        else -> super.synchronize(label)
    }

    override fun synchronizedFrom(label: EventLabel): Boolean = when {
        !isResponse -> false
        label is ParkLabel && label.isRequest && threadId == label.threadId -> true
        label is UnparkLabel && threadId == label.threadId -> true
        // TODO: provide an option to enable spurious wake-ups
        // label is InitializationLabel -> true
        else -> false
    }

    override fun toString(): String = super.toString()
}

/**
 * Label denoting unpark of a thread.
 *
 * Has [LabelKind.Send] kind.
 *
 * Can synchronize with [ParkLabel] request producing park-response label.
 *
 * @param threadId the identifier of unparked thread.
 *
 * @see ParkingEventLabel
 */
data class UnparkLabel(
    override val threadId: Int,
) : ParkingEventLabel(LabelKind.Send, threadId) {

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is ParkLabel)
            label.synchronize(this)
        else super.synchronize(label)

    override fun toString(): String = super.toString()
}