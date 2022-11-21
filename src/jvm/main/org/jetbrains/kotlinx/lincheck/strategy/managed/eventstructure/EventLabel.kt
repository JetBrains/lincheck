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

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryLocation
import org.jetbrains.kotlinx.lincheck.strategy.managed.OpaqueValue
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
 * to form a read-response label. The response label takes value written by write access
 * as its read value. When appropriate, we use notation `\+` to denote synchronization binary operation:
 *
 * ```
 * W(x, v) \+ R^{req}(x) = R^{rsp}(x, v)
 * ```
 *
 * Synchronize operation is expected to be associative and commutative.
 * It is partial operation --- some labels cannot participate in
 * synchronization (e.g. write access label cannot synchronize with lock acquire request).
 * In such cases [synchronize] returns null.
 *
 * ```
 * W(x, v) \+ Lk^{req}(l) = 0
 * ```
 *
 * It is responsibility of [EventLabel] subclasses to override [synchronize] method
 * in case when they need to implement non-trivial synchronization.
 * Default implementation only checks for synchronization against
 * special dummy [EmptyLabel] that behaves as a neutral element of [synchronize] operation.
 * Every label should be able to synchronize with [EmptyLabel] and produce itself.
 *
 * ```
 * W(x, v) \+ 1 = W(x, v)
 * ```
 *
 * Formally, labels form a synchronization algebra [1],
 * that is the algebraic structure (deriving from partial commutative monoid)
 * defining how individual atomic events can synchronize to produce new events.
 *
 * [1] Winskel, Glynn. "Event structure semantics for CCS and related languages."
 *     International Colloquium on Automata, Languages, and Programming. Springer, Berlin, Heidelberg, 1982.
 *
 */
abstract class EventLabel(
    /**
     * [LabelKind] of this label: send, request or response.
     *
     * @see EventLabel
     */
    open val kind: LabelKind,
    /**
     * Type of synchronization used by this label.
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
     */
    val syncType: SynchronizationType,
    /**
     * Whether this label is blocking.
     * Load acquire-request and thread join-request/response are examples of blocking labels.
     */
    val isBlocking: Boolean = false,
    /**
     * Whether this blocking label is already unblocked.
     * For example, thread join-response is unblocked when
     * all the threads it waits for have finished.
     */
    val unblocked: Boolean = true,
) {
    
    /**
     * Synchronizes event label with another label passed as a parameter.
     *
     * @see EventLabel
     */
    open infix fun synchronize(label: EventLabel): EventLabel? =
        if (label is EmptyLabel) this else null

    /**
     * Checks whether a pair of labels can synchronize
     * (do not confuse this term with "synchronizes-with" relation from the Java Memory Model).
     * Default implementation just checks that result of [synchronize] is not null,
     * overridden implementation can optimize this check.
     */
    open infix fun synchronizesWith(label: EventLabel): Boolean =
        (synchronize(label) != null)

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
     * Tries to replay this label using given argument label.
     *
     * Replaying can be used by an executor in order to reproduce execution saved as a list of labels.
     * Because some values of execution can change non-deterministically
     * between different invocations (for example, addresses of allocated objects),
     * replaying execution scenario may require to change some internal information
     * kept in event label, without modifying its shape.
     * For example, write access event should remain write access event,
     * but the address of the written memory location can change.
     *
     * @return true if replay is successfull, false otherwise.
     */
    open fun replay(label: EventLabel): Boolean =
        (this == label)

}

/**
 * Kind of label.
 *
 * @see EventLabel
 */
enum class LabelKind { Send, Request, Response }

/**
 * Type of synchronization used by label.
 *
 * @see EventLabel
 */
enum class SynchronizationType { Binary, Barrier }

// TODO: rename to BarrierRaceException?
class InvalidBarrierSynchronizationException(message: String): Exception(message)

/**
 * Dummy empty label acting as a neutral element of [synchronize] operation.
 *
 * For each label `l` it should be true that:
 *
 * ```
 * l \+ 1 = 1 \+ l = l
 * ```
 *
 */
class EmptyLabel: EventLabel(
    kind = LabelKind.Send,
    syncType = SynchronizationType.Binary,
) {

    override fun synchronize(label: EventLabel) = label

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
 * init \+ R^{req}(x) = R^{rsp}(x, 0)
 * ```
 */
class InitializationLabel : EventLabel(
    kind = LabelKind.Send,
    // TODO: can barrier-synchronizing events also utilize InitializationLabel?
    syncType = SynchronizationType.Binary,
) {

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is InitializationLabel) null else label.synchronize(this)

    override fun toString(): String = "Init"

}

/**
 * Base class for all thread event labels.
 */
abstract class ThreadEventLabel(
    kind: LabelKind,
    syncKind: SynchronizationType,
    isBlocking: Boolean = false,
    unblocked: Boolean = true,
): EventLabel(kind, syncKind, isBlocking, unblocked)

/**
 * Label representing fork of a set of threads.
 *
 * Has [LabelKind.Send] kind.
 * Can synchronize with [ThreadStartLabel].
 */
data class ThreadForkLabel(
    /**
     * A set of thread ids this fork spawns.
     */
    val forkThreadIds: Set<Int>,
): ThreadEventLabel(
    kind = LabelKind.Send,
    syncKind = SynchronizationType.Binary,
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
 * Thread start-request label can synchronize with
 * [InitializationLabel] (for main thread, see [isMainThread]) or with
 * [ThreadForkLabel] to produce thread start-response.
 */
data class ThreadStartLabel(
    /**
     * Kind of label.
     * Should either be [LabelKind.Request] or [LabelKind.Response].
     */
    override val kind: LabelKind,
    /**
     * Thread id of started thread.
     */
    val threadId: Int,
    /**
     * Whether this thread is main thread, i.e. thread
     * starting the execution of the whole program.
     */
    val isMainThread: Boolean = false,
): ThreadEventLabel(
    kind = kind,
    syncKind = SynchronizationType.Binary,
) {

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
            check(!isMainThread)
            ThreadStartLabel(
                threadId = threadId,
                kind = LabelKind.Response,
                isMainThread = false,
            )
        }

        else -> super.synchronize(label)
    }

    override fun toString(): String = "ThreadStart"

}

/**
 * Label of a virtual event put into the end of each thread.
 *
 * Has [LabelKind.Send] kind.
 * Can synchronize with [ThreadJoinLabel] or another [ThreadFinishLabel].
 * In the latter case the sets of [finishedThreadIds] of two labels are merged in the resulting label.
 *
 * Thread finish label is considered to be always blocked.
 */
data class ThreadFinishLabel(
    /**
     * Set of threads that have been finished.
     */
    val finishedThreadIds: Set<Int>
): ThreadEventLabel(
    kind = LabelKind.Send,
    syncKind = SynchronizationType.Barrier,
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
 */
data class ThreadJoinLabel(
    /**
     * Kind of label.
     * Should either be [LabelKind.Request] or [LabelKind.Response].
     */
    override val kind: LabelKind,
    /**
     * Set of threads this label awaits to join.
     */
    val joinThreadIds: Set<Int>,
): ThreadEventLabel(
    kind = kind,
    syncKind = SynchronizationType.Barrier,
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

    override fun toString(): String =
        "ThreadJoin(${joinThreadIds})"
}

/**
 * Base class of read and write shared memory access event labels.
 * Stores common information about memory access ---
 * such as accessing memory location and read or written value.
 */
sealed class MemoryAccessLabel(
    /**
     * Kind of label.
     */
    kind: LabelKind,
    /**
     * Memory location affected by this memory access.
     */
    protected open var location_: MemoryLocation,
    /**
     * Written value for write access, read value for read access.
     */
    protected open var value_: OpaqueValue?,
    /**
     * Class of written or read value.
     */
    open val kClass: KClass<*>,
    /**
     * Flag indicating whether this access is exclusive.
     * Memory accesses obtained as a result of executing
     * atomic read-modify-write instructions (such as CAS),
     * have this flag set.
     */
    open val isExclusive: Boolean = false
): EventLabel(
    kind = kind,
    syncType = SynchronizationType.Binary,
) {

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

    private fun equalUpToReplay(label: MemoryAccessLabel): Boolean =
        (kind == label.kind) &&
        (accessKind == label.accessKind) &&
        (kClass == label.kClass) &&
        (isExclusive == label.isExclusive)
        // TODO: check for locations compatibility

    override fun replay(label: EventLabel): Boolean {
        if (label is MemoryAccessLabel && equalUpToReplay(label)) {
            location_ = label.location
            value_ = label.value
            return true
        }
        return false
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
 * Label denoting write access to shared memory.
 *
 * Has [LabelKind.Send] kind.
 * Can synchronize with request [ReadAccessLabel] producing read-response label.
 *
 * @see MemoryAccessLabel
 */
data class WriteAccessLabel(
    /**
     * Memory location affected by this write.
     */
    override var location_: MemoryLocation,
    /**
     * Written value.
     */
    override var value_: OpaqueValue?,
    /**
     * Class of written value.
     */
    override val kClass: KClass<*>,
    /**
     * Exclusive access flag.
     */
    override val isExclusive: Boolean = false
): MemoryAccessLabel(
    kind = LabelKind.Send,
    location_ = location_,
    value_ = value_,
    kClass = kClass,
    isExclusive = isExclusive
) {

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is ReadAccessLabel)
            label.synchronize(this)
        else super.synchronize(label)

}

/**
 * Label denoting read access to shared memory.
 *
 * Can either be of [LabelKind.Request] or [LabelKind.Response] kind.
 * Read-request can synchronize either with [InitializationLabel] or with [WriteAccessLabel]
 * to produce read-response label. In the former case response will take default value
 * for given class, provided by [kClass] constructor argument.
 * In the latter case read-response will take value of the write label.
 *
 * @see MemoryAccessLabel
 */
data class ReadAccessLabel(
    /**
     * Kind of label.
     */
    override val kind: LabelKind,
    /**
     * Memory location of this read.
     */
    override var location_: MemoryLocation,
    /**
     * Read value. For read-request labels equals to null.
     */
    override var value_: OpaqueValue?,
    /**
     * Class of read value.
     */
    override val kClass: KClass<*>,
    /**
     * Exclusive access flag.
     */
    override val isExclusive: Boolean = false
): MemoryAccessLabel(
    kind = kind,
    location_ = location_,
    value_ = value_,
    kClass = kClass,
    isExclusive = isExclusive
) {

    init {
        require(isRequest || isResponse)
        require(isRequest implies (value == null))
    }

    override fun synchronize(label: EventLabel): EventLabel? = when {

        (isRequest && label is InitializationLabel) ->
            completeRequest(OpaqueValue.default(kClass))

        (isRequest && label is WriteAccessLabel && location == label.location) ->
            completeRequest(label.value)

        else -> super.synchronize(label)
    }

    private fun completeRequest(value: OpaqueValue?): ReadAccessLabel {
        require(isRequest)
        // require(value.isInstanceOf(kClass))
        return ReadAccessLabel(
            kind = LabelKind.Response,
            location_ = location,
            value_ = value,
            kClass = kClass,
            isExclusive = isExclusive,
        )
    }

}