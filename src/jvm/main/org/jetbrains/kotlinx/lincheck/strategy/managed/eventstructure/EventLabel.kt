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
import java.util.IdentityHashMap
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
 *
 * @param kind the kind of this label (see [LabelKind]).
 * @param isBlocking whether this label is blocking.
 *    Load acquire-request and thread join-request/response are examples of blocking labels.
 * @param unblocked whether this blocking label is already unblocked.
 *   For example, thread join-response is unblocked when all the threads it waits for have finished.
 */
sealed class EventLabel(
    open val kind: LabelKind,
    val isBlocking: Boolean = false,
    val unblocked: Boolean = true,
) {
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
     * Checks whether this label is recieve label.
     */
    val isReceive: Boolean
        get() = (kind == LabelKind.Receive)

    /**
     * Type of the label.
     *
     * @see LabelType
     */
    val type: LabelType
        get() = when (this) {
            is InitializationLabel          -> LabelType.Initialization
            is ThreadStartLabel             -> LabelType.ThreadStart
            is ThreadFinishLabel            -> LabelType.ThreadFinish
            is ThreadForkLabel              -> LabelType.ThreadFork
            is ThreadJoinLabel              -> LabelType.ThreadJoin
            is ObjectAllocationLabel        -> LabelType.ObjectAllocation
            is ReadAccessLabel              -> LabelType.ReadAccess
            is WriteAccessLabel             -> LabelType.WriteAccess
            is ReadModifyWriteAccessLabel   -> LabelType.ReadModifyWriteAccess
            is LockLabel                    -> LabelType.Lock
            is UnlockLabel                  -> LabelType.Unlock
            is WaitLabel                    -> LabelType.Wait
            is NotifyLabel                  -> LabelType.Notify
            is ParkLabel                    -> LabelType.Park
            is UnparkLabel                  -> LabelType.Unpark
        }

    /**
     * Object accesses by the operation represented by the label.
     * For example, for object field memory access labels, this is the accessed object.
     * If a particular subclass of labels does not access any object,
     * then this property is null.
     */
    open val obj: OpaqueValue? = null

    /**
     * An index of a label which is used to group semantically similar and
     * potentially concurrent labels operating on the same object or location.
     * For example, for object field memory access labels this is the accessed memory location.
     * Note that [index] and [obj] do not necessarily match. In case of object field memory access labels,
     * the [index] is a tuple of accessed object, accessed field name and class name,
     * while [obj] is only the accessed object itself.
     * If particular subclass of labels does not have natural index
     * then this property is null.
     */
    open val index: Any? = null

    /**
     * Checks whether this label is valid response to given [label] passed as argument.
     *
     * @throws IllegalArgumentException is this is not a response label.
     */
    open fun isValidResponse(label: EventLabel): Boolean {
        require(isResponse)
        return false
    }

    /**
     * Returns response of this label corresponding to the given [label].
     *
     * @throws IllegalArgumentException if this is not either request or response label.
     */
    open fun getResponse(label: EventLabel): EventLabel? {
        return null
    }

    /**
     * Checks whether this label is valid receive to given [label].
     *
     * @throws IllegalArgumentException is this is not receive label.
     */
    open fun isValidReceive(label: EventLabel): Boolean {
        require(isReceive)
        return false
    }

    /**
     * For this response label returns its corresponding receive label.
     * TODO: also make open function getResponse() ?
     *
     * @throws IllegalArgumentException is this is not response label.
     */
    open fun getReceive(): EventLabel? {
        require(isResponse)
        return null
    }

    /**
     * Partial join operation combining semantics of two labels.
     */
    open fun join(label: EventLabel): EventLabel? = null

    /**
     * Checks whether semantics of this label subsumes another label.
     * Should be consistent with respect to [join] operation:
     *   if `A join B = C` then `C subsumes A` and `C subsumes B`.
     */
    open fun subsumes(label: EventLabel): Boolean = false

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
     *
     * TODO: instead of storing actual object references in labels, we can store there object IDs,
     *   and then additionally keep a mapping `ObjectID -> Any`
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

}

/**
 * Kind of label. Can be one of the following:
 * - [LabelKind.Send] --- send label.
 * - [LabelKind.Request] --- request label.
 * - [LabelKind.Response] --- response label.
 * - [LabelKind.Receive] --- receive label.
 *
 * For example, [WriteAccessLabel] is an example of the send label,
 * while [ReadAccessLabel] is split into request and response part.
 *
 * @see EventLabel
 */
enum class LabelKind { Send, Request, Response, Receive }

enum class LabelType {
    Initialization,
    ThreadStart,
    ThreadFinish,
    ThreadFork,
    ThreadJoin,
    ObjectAllocation,
    ReadAccess,
    WriteAccess,
    ReadModifyWriteAccess,
    Lock,
    Unlock,
    Wait,
    Notify,
    Park,
    Unpark,
}

/**
 * Special label acting as a label of the virtual root event.
 *
 * Has [LabelKind.Send] kind.
 *
 * Initialization label can synchronize with various different labels.
 * Usually synchronization of initialization label with some request label
 * results in response label that obtains some default value.
 * For example, synchronizing initialization label with read-request label of integer
 * results in read-response label with value `0`.
 *
 * ```
 * Init \+ Read^{req}(x) = Read^{rsp}(x, 0)
 * ```
 */
// TODO: now that we have ObjectAllocationLabel we can get rid of it?
class InitializationLabel(
    val mainThreadID: ThreadID,
    val memoryInitializer: MemoryInitializer,
    val isExternalObject: (Any) -> Boolean,
) : EventLabel(LabelKind.Send) {

    private val objectsAllocations =
        IdentityHashMap<Any, ObjectAllocationLabel>()

    fun asThreadForkLabel() =
        ThreadForkLabel(setOf(mainThreadID))

    fun asObjectAllocationLabel(obj: Any): ObjectAllocationLabel? =
        if (isExternalObject(obj))
            objectsAllocations.computeIfAbsent(obj) {
                ObjectAllocationLabel(obj.opaque(), memoryInitializer)
            }
        else null

    fun asWriteAccessLabel(location: MemoryLocation) =
        asObjectAllocationLabel(location.obj)?.asWriteAccessLabel(location)

    fun asUnlockLabel(mutex: OpaqueValue) =
        asObjectAllocationLabel(mutex.unwrap())?.asUnlockLabel(mutex)

    override fun toString(): String = "Init"

}

/**
 * Base class for all thread event labels.
 *
 * @param kind the kind of this label.
 * @param syncType the synchronization type of this label.
 * @param isBlocking whether this label is blocking.
 * @param unblocked whether this blocking label is already unblocked.
  */
sealed class ThreadEventLabel(
    kind: LabelKind,
    isBlocking: Boolean = false,
    unblocked: Boolean = true,
): EventLabel(kind, isBlocking, unblocked)

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
): ThreadEventLabel(kind = LabelKind.Send) {

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
 */
data class ThreadStartLabel(
    override val kind: LabelKind,
    val threadId: Int,
): ThreadEventLabel(kind) {

    init {
        check(isRequest || isResponse || isReceive)
    }

    override fun isValidResponse(label: EventLabel): Boolean {
        require(isResponse)
        return when (label) {
            is ThreadStartLabel ->
                label.isRequest && threadId == label.threadId
            else ->
                label.asThreadForkLabel()
                    ?.let { threadId in it.forkThreadIds } ?: false
        }
    }

    override fun getResponse(label: EventLabel): ThreadStartLabel? = when {
        (isRequest || isResponse) ->
            label.asThreadForkLabel()
                ?.takeIf { isRequest && threadId in it.forkThreadIds }
                ?.let {
                    ThreadStartLabel(
                        kind = LabelKind.Response,
                        threadId = threadId,
                    )
                }
        else -> null
    }

    override fun isValidReceive(label: EventLabel): Boolean {
        require(isReceive)
        return label is ThreadStartLabel && !label.isReceive
                && threadId == label.threadId
    }

    override fun getReceive(): EventLabel {
        require(isResponse)
        return copy(kind = LabelKind.Receive)
    }

    // TODO: should we override `recipient` of `ThreadStartLabel` to be the thread id of the starting thread?

    override fun toString(): String =
        "ThreadStart"
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
    isBlocking = true,
    unblocked = false,
) {
    constructor(threadId: Int): this(setOf(threadId))

    override fun join(label: EventLabel): ThreadFinishLabel? =
        if (label is ThreadFinishLabel)
            ThreadFinishLabel(finishedThreadIds + label.finishedThreadIds)
        else null

    override fun subsumes(label: EventLabel): Boolean =
        if (label is ThreadFinishLabel)
            finishedThreadIds.containsAll(label.finishedThreadIds)
        else false

    override fun toString(): String =
        "ThreadFinish"
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
    isBlocking = true,
    unblocked = (kind == LabelKind.Response || kind == LabelKind.Receive) implies joinThreadIds.isEmpty(),
) {

    init {
        require(isRequest || isResponse || isReceive)
        require(isReceive implies unblocked)
    }

    override fun isValidResponse(label: EventLabel): Boolean {
        require(isResponse)
        return when (label) {
            is ThreadJoinLabel ->
                label.joinThreadIds.containsAll(joinThreadIds)
            is ThreadFinishLabel ->
                label.finishedThreadIds.all { it !in joinThreadIds }
            else -> false
        }
    }

    override fun getResponse(label: EventLabel): EventLabel? = when {
        (isRequest || isResponse) && label is ThreadFinishLabel && joinThreadIds.containsAll(label.finishedThreadIds) ->
            ThreadJoinLabel(
                kind = LabelKind.Response,
                joinThreadIds = joinThreadIds - label.finishedThreadIds,
            )
        else -> null
    }

    override fun isValidReceive(label: EventLabel): Boolean {
        require(isReceive)
        return label is ThreadJoinLabel && !label.isReceive
                && unblocked && label.unblocked
    }

    override fun getReceive(): EventLabel? {
        require(isResponse)
        return if (unblocked)
            copy(kind = LabelKind.Receive)
        else null
    }

    override fun toString(): String =
        "ThreadJoin(${joinThreadIds})"
}

fun EventLabel.asThreadForkLabel(): ThreadForkLabel? = when (this) {
    is ThreadForkLabel -> this
    is InitializationLabel -> asThreadForkLabel()
    else -> null
}

fun EventLabel.asThreadEventLabel(): ThreadEventLabel? = when (this) {
    is ThreadEventLabel -> this
    is InitializationLabel -> asThreadForkLabel()
    else -> null
}

data class ObjectAllocationLabel(
    private var _obj: OpaqueValue,
    val memoryInitializer: MemoryInitializer,
) : EventLabel(kind = LabelKind.Send) {

    override val obj: OpaqueValue
        get() = _obj

    // TODO: reset on replay!
    private val initialValues = HashMap<MemoryLocation, OpaqueValue>()

    private fun initialValue(location: MemoryLocation): OpaqueValue? {
        require(location.obj === obj.unwrap())
        return initialValues
            .computeIfAbsent(location) {
                memoryInitializer(it) ?: NULL
            }
            .takeIf { it != NULL }
    }

    fun asWriteAccessLabel(location: MemoryLocation) =
        if (location.obj === obj.unwrap())
            WriteAccessLabel(
                location = location,
                _value = initialValue(location),
                isExclusive = false,
            )
        else null

    fun asUnlockLabel(mutex: OpaqueValue) =
        if (mutex == obj) UnlockLabel(mutex_ = obj, isInitUnlock = true) else null

    /**
     * Replays this object allocation label using another allocation label given as argument.
     * Replaying can substitute the allocated object.
     *
     * @see EventLabel.replay
     */
    override fun replay(label: EventLabel, remapping: Remapping) {
        // TODO: check type of objects?
        check(label is ObjectAllocationLabel) {
            "Event label $this cannot be replayed by $label"
        }
        remapping[obj.unwrap()] = label.obj.unwrap()
        _obj = label._obj
    }

    /**
     * Remaps memory location and value according to given [remapping].
     *
     * @see EventLabel.replay
     */
    override fun remap(remapping: Remapping) {
        remapping[obj]?.also { _obj = it.opaque() }
    }

    override fun toString(): String =
        "Alloc($obj)"

    companion object {
        private val NULL : OpaqueValue = Any().opaque()
    }

}

fun EventLabel.asObjectAllocationLabel(obj: OpaqueValue): ObjectAllocationLabel? = when (this) {
    is ObjectAllocationLabel -> takeIf { it.obj == obj }
    is InitializationLabel -> asObjectAllocationLabel(obj.unwrap())
    else -> null
}

/**
 * Base class of read and write shared memory access event labels.
 * Stores common information about memory access ---
 * such as accessing memory location and read or written value.
 *
 * @param kind the kind of this label.
 * @param location memory location affected by this memory access.
 * @param value_ written value for write access, read value for read access.
 * @param kClass class of written or read value.
 * @param isExclusive flag indicating whether this access is exclusive.
 *   Memory accesses obtained as a result of executing atomic read-modify-write
 *   instructions (such as CAS) have this flag set.
 */
sealed class MemoryAccessLabel(
    kind: LabelKind,
    open val location: MemoryLocation,
    open val kClass: KClass<*>?,
    open val isExclusive: Boolean = false
): EventLabel(kind) {

    /**
     * Read value for read access.
     */
    abstract val readValue: OpaqueValue?

    /**
     * Written value for write access.
     */
    abstract val writeValue: OpaqueValue?

    /**
     * Kind of memory access of this label:
     * either [MemoryAccessKind.Write] or [MemoryAccessKind.Read].
     */
    val accessKind: MemoryAccessKind
        get() = when(this) {
            is WriteAccessLabel -> MemoryAccessKind.Write
            is ReadAccessLabel -> MemoryAccessKind.Read
            is ReadModifyWriteAccessLabel -> MemoryAccessKind.ReadModifyWrite
        }

    /**
     * Checks whether this memory access is read.
     */
    val isRead: Boolean
        get() = (accessKind == MemoryAccessKind.Read || accessKind == MemoryAccessKind.ReadModifyWrite)

    /**
     * Checks whether this memory access is write.
     */
    val isWrite: Boolean
        get() = (accessKind == MemoryAccessKind.Write || accessKind == MemoryAccessKind.ReadModifyWrite)

    /**
     * Recipient of a memory access label is equal to its memory location's object.
     */
    override val obj: OpaqueValue?
        get() = location.obj.opaque()

    /**
     * Index of a memory access label is equal to accessed memory location.
     */
    override val index: Any?
        get() = location

    /**
     * Checks whether this label can be replayed by another [label].
     */
    private fun replayable(label: MemoryAccessLabel): Boolean =
        kind == label.kind &&
        kClass == label.kClass &&
        isExclusive == label.isExclusive &&
        accessKind == label.accessKind

    /**
     * Replays this memory access label using another memory access label given as argument.
     * Replaying can substitute accessed memory location and read/written value of the memory access.
     *
     * @see EventLabel.replay
     */
    override fun replay(label: EventLabel, remapping: Remapping) {
        check(label is MemoryAccessLabel && replayable(label)) {
            "Event label $this cannot be replayed by $label"
        }
        location.replay(label.location, remapping)
    }

    /**
     * Remaps memory location and value according to given [remapping].
     *
     * @see EventLabel.replay
     */
    override fun remap(remapping: Remapping) {
        location.remap(remapping)
    }

    override fun toString(): String {
        val kindString = when (kind) {
            LabelKind.Send -> ""
            LabelKind.Request -> "^req"
            LabelKind.Response -> "^rsp"
            LabelKind.Receive -> ""
        }
        val exclString = if (isExclusive) "_ex" else ""
        val argsString = listOfNotNull(
            "$location",
            if (isRead && kind != LabelKind.Request) "$readValue" else null,
            if (isWrite) "$writeValue" else null,
        ).joinToString()
        return "${accessKind}${kindString}${exclString}(${argsString})"
    }

}

/**
 * Kind of memory access.
 *
 * @see MemoryAccessLabel
 */
enum class MemoryAccessKind { Read, Write, ReadModifyWrite }

/**
 * Label denoting read access to shared memory.
 *
 * @param kind the kind of this label: [LabelKind.Request] or [LabelKind.Response] kind.
 * @param location memory location of this read.
 * @param _value the read value, for read-request label equals to null.
 * @param kClass the class of read value.
 * @param isExclusive exclusive access flag.
 *
 * @see MemoryAccessLabel
 */
data class ReadAccessLabel(
    override val kind: LabelKind,
    override val location: MemoryLocation,
    private var _value: OpaqueValue?,
    override val kClass: KClass<*>?,
    override val isExclusive: Boolean = false
): MemoryAccessLabel(kind, location, kClass, isExclusive) {

    init {
        require(isRequest || isResponse || isReceive)
        require(isRequest implies (value == null))
    }

    val value: OpaqueValue?
        get() = _value

    override val readValue: OpaqueValue?
        get() = value

    override val writeValue: OpaqueValue?
        get() = null

    override fun isValidResponse(label: EventLabel): Boolean {
        require(isResponse)
        return when (label) {
            is ReadAccessLabel ->
                label.isRequest &&
                kClass == label.kClass &&
                location == label.location &&
                isExclusive == label.isExclusive

            else -> label.asWriteAccessLabel(location)?.let {
                // TODO: also check kClass
                value == it.value
            } ?: false
        }
    }

    override fun getResponse(label: EventLabel): EventLabel? = when {
        isRequest ->
            label.asWriteAccessLabel(location)?.let {
                // TODO: perform dynamic type-check
                ReadAccessLabel(
                    kind = LabelKind.Response,
                    location = location,
                    kClass = kClass,
                    _value = it.value,
                    isExclusive = isExclusive,
                )
            }
        else -> null
    }

    override fun isValidReceive(label: EventLabel): Boolean {
        require(isReceive)
        return label is ReadAccessLabel && !label.isReceive
                && kClass == label.kClass
                && location == label.location
                && isExclusive == label.isExclusive
                && value == label.value
    }

    override fun getReceive(): ReadAccessLabel {
        require(isResponse)
        return copy(kind = LabelKind.Receive)
    }

    override fun replay(label: EventLabel, remapping: Remapping) {
        super.replay(label, remapping)
        (label as ReadAccessLabel)
        if (value != null && label.value != null) {
            // TODO: check primitive values are equal
            remapping[value?.unwrap()] = label.value?.unwrap()
        }
        _value = label.value
    }

    override fun remap(remapping: Remapping) {
        super.remap(remapping)
        value?.unwrap()
            ?.let { remapping[it] }
            ?.also { _value = it.opaque() }
    }

    override fun toString(): String =
        super.toString()
}

/**
 * Label denoting write access to shared memory.
 *
 * @param location the memory location affected by this write.
 * @param _value the written value.
 * @param kClass the class of written value.
 * @param isExclusive exclusive access flag.
 *
 * @see MemoryAccessLabel
 */
data class WriteAccessLabel(
    override val location: MemoryLocation,
    private var _value: OpaqueValue?,
    override val kClass: KClass<*>? = _value?.unwrap()?.javaClass?.kotlin,
    override val isExclusive: Boolean = false
): MemoryAccessLabel(LabelKind.Send, location, kClass, isExclusive) {

    val value: OpaqueValue?
        get() = _value

    override val readValue: OpaqueValue?
        get() = null

    override val writeValue: OpaqueValue?
        get() = value

    override fun replay(label: EventLabel, remapping: Remapping) {
        super.replay(label, remapping)
        // TODO: check primitive values are equal
        remapping[value?.unwrap()] = (label as WriteAccessLabel).value?.unwrap()
        _value = label.value
    }

    override fun remap(remapping: Remapping) {
        super.remap(remapping)
        value?.unwrap()
            ?.let { remapping[it] }
            ?.also { _value = it.opaque() }
    }

    override fun toString(): String =
        super.toString()
}

/**
 * Label denoting read-modify-write (RMW) access to shared memory
 * (e.g. compare-and-swap, get-and-increment, etc).
 *
 * @param location the memory location affected by this write.
 * @param _readValue the read value.
 * @param _writeValue the written value.
 * @param kClass the class of written value.
 * @param isExclusive exclusive access flag.
 *
 * @see MemoryAccessLabel
 */
data class ReadModifyWriteAccessLabel(
    override val kind: LabelKind,
    override val location: MemoryLocation,
    private var _readValue: OpaqueValue?,
    private var _writeValue: OpaqueValue?,
    override val kClass: KClass<*>? = _writeValue?.unwrap()?.javaClass?.kotlin
): MemoryAccessLabel(kind, location, kClass, isExclusive = true) {

    init {
        require(kind == LabelKind.Response || kind == LabelKind.Receive)
    }

    override val readValue: OpaqueValue?
        get() = _readValue

    override val writeValue: OpaqueValue?
        get() = _writeValue

    override fun isValidReceive(label: EventLabel): Boolean {
        require(isReceive)
        return label is ReadModifyWriteAccessLabel && !label.isReceive
                && kClass == label.kClass
                && location == label.location
                && isExclusive == label.isExclusive
                && readValue == label.readValue
                && writeValue == label.writeValue
    }

    override fun getReceive(): ReadModifyWriteAccessLabel {
        require(isResponse)
        return copy(kind = LabelKind.Receive)
    }

    override fun replay(label: EventLabel, remapping: Remapping) {
        super.replay(label, remapping)
        // TODO: check primitive values are equal
        check(label is ReadModifyWriteAccessLabel)
        remapping[readValue?.unwrap()] = label.readValue?.unwrap()
        remapping[writeValue?.unwrap()] = label.writeValue?.unwrap()
        _readValue = label._readValue
        _writeValue = label._writeValue
    }

    override fun remap(remapping: Remapping) {
        super.remap(remapping)
        readValue?.unwrap()
            ?.let { remapping[it] }
            ?.also { _readValue = it.opaque() }
        writeValue?.unwrap()
            ?.let { remapping[it] }
            ?.also { _writeValue = it.opaque() }
    }

    override fun toString(): String =
        super.toString()
}

fun ReadModifyWriteAccessLabel(read: ReadAccessLabel, write: WriteAccessLabel) : ReadModifyWriteAccessLabel? {
    require(read.kind == LabelKind.Response || read.kind == LabelKind.Receive)
    return if (read.kClass == write.kClass &&
               read.location == write.location &&
               read.isExclusive == write.isExclusive)
        ReadModifyWriteAccessLabel(
            kind = read.kind,
            kClass = read.kClass,
            location = read.location,
            _readValue = read.value,
            _writeValue = write.value,
        )
    else null
}

fun ReadAccessLabel.isValidReadPart(label: ReadModifyWriteAccessLabel): Boolean =
    kClass == label.kClass &&
    location == label.location &&
    isExclusive == label.isExclusive &&
    (isResponse implies (readValue == label.readValue))

fun WriteAccessLabel.isValidWritePart(label: ReadModifyWriteAccessLabel): Boolean =
    kClass == label.kClass &&
    location == label.location &&
    isExclusive == label.isExclusive &&
    writeValue == label.writeValue

fun EventLabel.asWriteAccessLabel(location: MemoryLocation): WriteAccessLabel? = when (this) {
    is WriteAccessLabel -> this.takeIf { it.location == location }
    is ObjectAllocationLabel -> asWriteAccessLabel(location)
    is InitializationLabel -> asWriteAccessLabel(location)
    else -> null
}

fun EventLabel.asMemoryAccessLabel(location: MemoryLocation): MemoryAccessLabel? = when (this) {
    is MemoryAccessLabel -> this.takeIf { it.location == location }
    is ObjectAllocationLabel -> asWriteAccessLabel(location)
    is InitializationLabel -> asWriteAccessLabel(location)
    else -> null
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
): EventLabel(kind, isBlocking, unblocked) {

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
    override val obj: OpaqueValue?
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
            LabelKind.Receive -> ""
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
    val isWaitLock: Boolean = false,
) : MutexLabel(
    kind = kind,
    mutex_ = mutex_,
    isBlocking = true,
    unblocked = (kind != LabelKind.Request),
) {
    init {
        require(isRequest || isResponse)
        require(reentranceDepth - reentranceCount >= 0)
        // TODO: checks for non-reentrant locks
    }

    val isReentry: Boolean =
        (reentranceDepth - reentranceCount > 0)

    override fun isValidResponse(label: EventLabel): Boolean {
        require(isResponse)
        return when (label) {
            is LockLabel ->
                label.isRequest
                && mutex == label.mutex
                && reentranceDepth == label.reentranceDepth
                && reentranceCount == label.reentranceCount

            else ->
                label.asUnlockLabel(mutex)?.let {
                    !it.isReentry && (isReentry implies it.isInitUnlock)
                } ?: false
        }
    }

    override fun getResponse(label: EventLabel): LockLabel? = when {
        isRequest ->
            label.asUnlockLabel(mutex)
                ?.takeIf { !it.isReentry && (isReentry implies it.isInitUnlock) }
                ?.let {
                    LockLabel(
                        kind = LabelKind.Response,
                        mutex_ = mutex,
                        reentranceDepth = reentranceDepth,
                        reentranceCount = reentranceCount,
                    )
                }
        else -> null
    }

    override fun toString(): String =
        super.toString()
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
    // TODO: get rid of this!
    val isWaitUnlock: Boolean = false,
    // TODO: get rid of this!
    val isInitUnlock: Boolean = false,
) : MutexLabel(LabelKind.Send, mutex_) {

    init {
        // TODO: checks for non-reentrant locks
        require(reentranceDepth - reentranceCount >= 0)
    }

    val isReentry: Boolean =
        (reentranceDepth - reentranceCount > 0)

    override fun toString(): String =
        super.toString()
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
    val unlocking: Boolean = false,
    val locking: Boolean = false,
) : MutexLabel(
    kind = kind,
    mutex_ = mutex_,
    isBlocking = true,
    unblocked = (kind != LabelKind.Request),
) {
    init {
        require(isRequest || isResponse)
        require(isRequest implies !locking)
        require(isResponse implies !unlocking)
    }

    override fun isValidResponse(label: EventLabel): Boolean {
        require(isResponse)
        return when (label) {
            is WaitLabel ->
                label.isRequest && mutex == label.mutex
            else ->
                label.asNotifyLabel(mutex) != null
        }
    }

    override fun getResponse(label: EventLabel): WaitLabel? = when {
        isRequest -> label.asNotifyLabel(mutex)?.let {
            WaitLabel(LabelKind.Response, mutex)
        }
        else -> null
    }

    override fun toString(): String =
        super.toString()
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

    override fun toString(): String =
        super.toString()
}

fun EventLabel.asUnlockLabel(mutex: OpaqueValue): UnlockLabel? = when (this) {
    is UnlockLabel -> this.takeIf { it.mutex == mutex }
    is ObjectAllocationLabel -> asUnlockLabel(mutex)
    is InitializationLabel -> asUnlockLabel(mutex)
    else -> null
}

fun EventLabel.asNotifyLabel(mutex: OpaqueValue): NotifyLabel? = when (this) {
    is NotifyLabel -> this.takeIf { it.mutex == mutex }
    else -> null
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
): EventLabel(kind, isBlocking, unblocked) {

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
            LabelKind.Receive -> ""
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
    unblocked = (kind != LabelKind.Request),
) {
    init {
        require(isRequest || isResponse)
    }

    override fun isValidResponse(label: EventLabel): Boolean {
        require(isResponse)
        return when (label) {
            is ParkingEventLabel ->
                (label.isRequest || label.isSend) && threadId == label.threadId
            else -> false
        }
    }

    override fun getResponse(label: EventLabel): ParkLabel? = when {
        isRequest && label is UnparkLabel && threadId == label.threadId ->
            ParkLabel(LabelKind.Response, threadId)
        // TODO: provide an option to enable spurious wake-ups
        else -> null
    }

    override fun isValidReceive(label: EventLabel): Boolean {
        require(isReceive)
        return label is ParkLabel && !label.isReceive
                && threadId == label.threadId
    }

    override fun getReceive(): EventLabel {
        require(isResponse)
        return copy(kind = LabelKind.Receive)
    }

    override fun toString(): String =
        super.toString()
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

    override fun toString(): String =
        super.toString()
}