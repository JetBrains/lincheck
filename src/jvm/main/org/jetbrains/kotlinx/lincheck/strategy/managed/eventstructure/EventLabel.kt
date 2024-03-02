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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.utils.*
import java.util.IdentityHashMap
import kotlin.reflect.KClass

/**
 * EventLabel is a base class for the hierarchy of classes
 * representing semantic labels of various events performed by the program.
 *
 * It includes events such as
 * - thread fork, join, start, or finish;
 * - reads and writes from/to shared memory;
 * - mutex locks and unlocks;
 * and other (see subclasses of this class).
 *
 *
 * @property kind the kind of this label (see [LabelKind]).
 * @property isBlocking flag indicating that label is blocking.
 *    Mutex lock and thread join are examples of blocking labels.
 * @property isUnblocked flag indicating that blocking label is unblocked.
 *   For example, thread join-response is unblocked when all the threads it waits for have finished.
 *   For non-blocking labels, it should be set to true.
 */
sealed class EventLabel(
    open val kind: LabelKind,
    val isBlocking: Boolean = false,
    val isUnblocked: Boolean = true,
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
     * Object accesses by the operation represented by the label.
     * For example, for object field memory access labels, this is the accessed object.
     * If a particular subclass of labels does not access any object,
     * then this property is equal to [NULL_OBJECT_ID].
     */
    open val objectID: ObjectID = NULL_OBJECT_ID

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

val LabelKind.repr get() = when (this) {
    LabelKind.Send -> ""
    LabelKind.Request -> "^req"
    LabelKind.Response -> "^rsp"
    LabelKind.Receive -> ""
}

/**
 * Enum class representing different types of event labels.
 */
enum class LabelType {
    Initialization,
    ObjectAllocation,
    ThreadStart,
    ThreadFinish,
    ThreadFork,
    ThreadJoin,
    ReadAccess,
    WriteAccess,
    ReadModifyWriteAccess,
    CoroutineSuspend,
    CoroutineResume,
    Lock,
    Unlock,
    Wait,
    Notify,
    Park,
    Unpark,
    Actor,
    Random,
}

/**
 * Type of the label.
 *
 * @see LabelType
 */
val EventLabel.type: LabelType get() = when (this) {
    is InitializationLabel          -> LabelType.Initialization
    is ObjectAllocationLabel        -> LabelType.ObjectAllocation
    is ThreadStartLabel             -> LabelType.ThreadStart
    is ThreadFinishLabel            -> LabelType.ThreadFinish
    is ThreadForkLabel              -> LabelType.ThreadFork
    is ThreadJoinLabel              -> LabelType.ThreadJoin
    is ReadAccessLabel              -> LabelType.ReadAccess
    is WriteAccessLabel             -> LabelType.WriteAccess
    is ReadModifyWriteAccessLabel   -> LabelType.ReadModifyWriteAccess
    is CoroutineSuspendLabel        -> LabelType.CoroutineSuspend
    is CoroutineResumeLabel         -> LabelType.CoroutineResume
    is LockLabel                    -> LabelType.Lock
    is UnlockLabel                  -> LabelType.Unlock
    is WaitLabel                    -> LabelType.Wait
    is NotifyLabel                  -> LabelType.Notify
    is ParkLabel                    -> LabelType.Park
    is UnparkLabel                  -> LabelType.Unpark
    is ActorLabel                   -> LabelType.Actor
    is RandomLabel                  -> LabelType.Random
}

/**
 * A special label of the virtual root event of every execution.
 *
 * Initialization label stores the initial values for:
 *   - static memory locations, and
 *   - memory locations of external objects - that is objects
 *     allocated in the untracked code sections.
 *
 * @property initThreadID thread id used for the special initial thread;
 *   this thread should contain only the initialization event itself.
 * @property mainThreadID thread id of the main thread, starting the execution of a program.
 * @property memoryInitializer a callback performing a load of the initial values
 *   of a passed memory location.
 */
class InitializationLabel(
    val initThreadID: ThreadID,
    val mainThreadID: ThreadID,
    val memoryInitializer: MemoryIDInitializer,
) : EventLabel(LabelKind.Send) {

    private val staticMemory =
        HashMap<StaticFieldMemoryLocation, ValueID>()

    private val _objectsAllocations =
        IdentityHashMap<ObjectID, ObjectAllocationLabel>()

    val objectsAllocations: Map<ObjectID, ObjectAllocationLabel>
        get() = _objectsAllocations

    val externalObjects: Set<ObjectID>
        get() = objectsAllocations.keys

    fun getInitialValue(location: StaticFieldMemoryLocation): ValueID {
        return staticMemory.computeIfAbsent(location) { memoryInitializer(it) }
    }

    fun trackExternalObject(className: String, objID: ObjectID) {
        _objectsAllocations[objID] = ObjectAllocationLabel(className, objID, memoryInitializer)
    }

    override fun toString(): String = "Init"

}

fun InitializationLabel.asThreadForkLabel() =
    ThreadForkLabel(setOf(mainThreadID))

fun InitializationLabel.asObjectAllocationLabel(objID: ObjectID): ObjectAllocationLabel? =
    objectsAllocations[objID]

fun InitializationLabel.isWriteAccessTo(location: MemoryLocation): Boolean =
    location is StaticFieldMemoryLocation || (location.objID in externalObjects)

fun InitializationLabel.asWriteAccessLabel(location: MemoryLocation): WriteAccessLabel? = when {
    location is StaticFieldMemoryLocation ->
        WriteAccessLabel(
            location = location,
            value = getInitialValue(location),
            codeLocation = INIT_CODE_LOCATION,
            isExclusive = false,
        )

    else -> asObjectAllocationLabel(location.objID)?.asWriteAccessLabel(location)
}

fun InitializationLabel.asUnlockLabel(mutex: ObjectID) =
    asObjectAllocationLabel(mutex)?.asUnlockLabel(mutex)

private const val INIT_CODE_LOCATION = -1


data class ObjectAllocationLabel(
    val className: String,
    override val objectID: ObjectID,
    val memoryInitializer: MemoryIDInitializer,
) : EventLabel(kind = LabelKind.Send) {

    init {
        require(objectID != NULL_OBJECT_ID)
    }

    private val initialValues = HashMap<MemoryLocation, ValueID>()

    private fun initialValue(location: MemoryLocation): ValueID {
        require(location.objID == objectID)
        return initialValues.computeIfAbsent(location) { memoryInitializer(it) }
    }

    fun isWriteAccessTo(location: MemoryLocation) =
        (location.objID == objectID)

    fun asWriteAccessLabel(location: MemoryLocation) =
        if (location.objID == objectID)
            WriteAccessLabel(
                location = location,
                value = initialValue(location),
                isExclusive = false,
                // TODO: use actual allocation-site code location?
                codeLocation = INIT_CODE_LOCATION,
            )
        else null

    fun asUnlockLabel(mutex: ObjectID) =
        if (mutex == objectID) UnlockLabel(mutex = objectID, isInitUnlock = true) else null

    override fun toString(): String =
        "Alloc(${objRepr(className, objectID)})"

}

/**
 * Base class for all thread event labels.
 *
 * @param kind the kind of this label.
 * @param isBlocking flag indicating that label is blocking.
 * @param isUnblocked flag indicating that blocking label is unblocked.
  */
sealed class ThreadEventLabel(
    kind: LabelKind,
    isBlocking: Boolean = false,
    isUnblocked: Boolean = true,
): EventLabel(
    kind = kind,
    isBlocking = isBlocking,
    isUnblocked = isUnblocked
)

/**
 * Label representing fork of a set of threads.
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
 * Label of a virtual event put into the beginning of each thread.
 *
 * @param kind the kind of this label: [LabelKind.Request], [LabelKind.Response] or [LabelKind.Receive].
 * @param threadId thread id of starting thread.
 */
data class ThreadStartLabel(
    override val kind: LabelKind,
    val threadId: Int,
): ThreadEventLabel(kind) {

    init {
        require(isRequest || isResponse || isReceive)
    }

    override fun toString(): String =
        "ThreadStart${kind.repr}"
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
    isUnblocked = false,
) {
    constructor(threadId: Int): this(setOf(threadId))

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
 * This is blocking label, it becomes [isUnblocked] when set of
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
    isUnblocked = (kind != LabelKind.Request) implies joinThreadIds.isEmpty(),
) {

    init {
        require(isRequest || isResponse || isReceive)
        require(isReceive implies isUnblocked)
    }

    override fun toString(): String =
        "ThreadJoin${kind.repr}(${joinThreadIds})"
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

fun EventLabel.asObjectAllocationLabel(objID: ObjectID): ObjectAllocationLabel? = when (this) {
    is ObjectAllocationLabel -> takeIf { it.objectID == objID }
    is InitializationLabel -> asObjectAllocationLabel(objID)
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
    open val codeLocation: Int,
    open val location: MemoryLocation,
    open val kClass: KClass<*>?,
    open val isExclusive: Boolean = false
): EventLabel(kind) {

    /**
     * Read value for read access.
     */
    abstract val readValue: ValueID

    /**
     * Written value for write access.
     */
    abstract val writeValue: ValueID

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
    override val objectID: ObjectID
        get() = location.objID

    override fun toString(): String {
        val exclString = if (isExclusive) "_ex" else ""
        val argsString = listOfNotNull(
            "$location",
            if (isRead && kind != LabelKind.Request) "$readValue" else null,
            if (isWrite) "$writeValue" else null,
        ).joinToString()
        return "${accessKind}${kind.repr}${exclString}(${argsString})"
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
    override val codeLocation: Int,
    override val location: MemoryLocation,
    val value: ValueID,
    override val kClass: KClass<*>? = null,
    override val isExclusive: Boolean = false
): MemoryAccessLabel(kind, codeLocation, location, kClass, isExclusive) {

    init {
        require(isRequest || isResponse || isReceive)
        require(isRequest implies (value == NULL_OBJECT_ID))
    }

    override val readValue: ValueID
        get() = value

    override val writeValue: ValueID = NULL_OBJECT_ID

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
    override val codeLocation: Int,
    override val location: MemoryLocation,
    val value: ValueID,
    override val kClass: KClass<*>? = null,
    override val isExclusive: Boolean = false
): MemoryAccessLabel(LabelKind.Send, codeLocation, location, kClass, isExclusive) {

    override val readValue: ValueID = NULL_OBJECT_ID

    override val writeValue: ValueID
        get() = value

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
    override val codeLocation: Int,
    override val location: MemoryLocation,
    override val readValue: ValueID,
    override val writeValue: ValueID,
    override val kClass: KClass<*>? = null,
): MemoryAccessLabel(kind, codeLocation, location, kClass, isExclusive = true) {

    init {
        require(kind == LabelKind.Response || kind == LabelKind.Receive)
    }

    override fun toString(): String =
        super.toString()
}

fun ReadModifyWriteAccessLabel(read: ReadAccessLabel, write: WriteAccessLabel): ReadModifyWriteAccessLabel? {
    require(read.kind == LabelKind.Response || read.kind == LabelKind.Receive)
    return if (read.kClass == write.kClass &&
               read.location == write.location &&
               read.isExclusive == write.isExclusive &&
               read.codeLocation == write.codeLocation)
        ReadModifyWriteAccessLabel(
            kind = read.kind,
            codeLocation = read.codeLocation,
            kClass = read.kClass,
            location = read.location,
            readValue = read.value,
            writeValue = write.value,
        )
    else null
}

fun ReadAccessLabel.getReadModifyWrite(write: WriteAccessLabel): ReadModifyWriteAccessLabel? =
    ReadModifyWriteAccessLabel(this, write)

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

fun EventLabel.isWriteAccess(): Boolean =
    this is InitializationLabel || this is ObjectAllocationLabel || this is WriteAccessLabel

fun EventLabel.isExclusiveWriteAccess(): Boolean =
    (this is WriteAccessLabel) && isExclusive

fun EventLabel.isInitializingWriteAccess(): Boolean =
    this is InitializationLabel || this is ObjectAllocationLabel

fun EventLabel.isWriteAccessTo(location: MemoryLocation): Boolean = when (this) {
    is WriteAccessLabel         -> (this.location == location)
    is ObjectAllocationLabel    -> isWriteAccessTo(location)
    is InitializationLabel      -> isWriteAccessTo(location)
    else -> false
}

fun EventLabel.asWriteAccessLabel(location: MemoryLocation): WriteAccessLabel? = when (this) {
    is WriteAccessLabel         -> this.takeIf { it.location == location }
    is ObjectAllocationLabel    -> asWriteAccessLabel(location)
    is InitializationLabel      -> asWriteAccessLabel(location)
    else -> null
}

fun EventLabel.isMemoryAccessTo(location: MemoryLocation): Boolean = when (this) {
    is MemoryAccessLabel        -> (this.location == location)
    is ObjectAllocationLabel    -> isWriteAccessTo(location)
    is InitializationLabel      -> isWriteAccessTo(location)
    else -> false
}

fun EventLabel.asMemoryAccessLabel(location: MemoryLocation): MemoryAccessLabel? = when (this) {
    is MemoryAccessLabel        -> this.takeIf { it.location == location }
    is ObjectAllocationLabel    -> asWriteAccessLabel(location)
    is InitializationLabel      -> asWriteAccessLabel(location)
    else -> null
}

/**
 * Base class of all mutex operations event labels.
 *
 * @param kind the kind of this label.
 * @param mutex id of the mutex to perform operation on.
 * @param isBlocking whether this label is blocking.
 * @param unblocked whether this blocking label is already unblocked.
 */
sealed class MutexLabel(
    kind: LabelKind,
    open val mutex: ObjectID,
    isBlocking: Boolean = false,
    unblocked: Boolean = true,
): EventLabel(kind, isBlocking, unblocked) {

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
    override val objectID: ObjectID
        get() = mutex

    override fun toString(): String {
        return "${operationKind}${kind.repr}($mutex)"
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
    override val mutex: ObjectID,
    val reentranceDepth: Int = 1,
    val reentranceCount: Int = 1,
    val isWaitLock: Boolean = false,
) : MutexLabel(
    kind = kind,
    mutex = mutex,
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
    override val mutex: ObjectID,
    val reentranceDepth: Int = 1,
    val reentranceCount: Int = 1,
    // TODO: get rid of this!
    val isWaitUnlock: Boolean = false,
    // TODO: get rid of this!
    val isInitUnlock: Boolean = false,
) : MutexLabel(LabelKind.Send, mutex) {

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
    override val mutex: ObjectID,
    val unlocking: Boolean = false,
    val locking: Boolean = false,
) : MutexLabel(
    kind = kind,
    mutex = mutex,
    isBlocking = true,
    unblocked = (kind != LabelKind.Request),
) {
    init {
        require(isRequest || isResponse)
        require(isRequest implies !locking)
        require(isResponse implies !unlocking)
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
    override val mutex: ObjectID,
    val isBroadcast: Boolean
) : MutexLabel(LabelKind.Send, mutex) {

    override fun toString(): String =
        super.toString()
}

fun EventLabel.asUnlockLabel(mutex: ObjectID): UnlockLabel? = when (this) {
    is UnlockLabel -> this.takeIf { it.mutex == mutex }
    is ObjectAllocationLabel -> asUnlockLabel(mutex)
    is InitializationLabel -> asUnlockLabel(mutex)
    else -> null
}

fun EventLabel.asNotifyLabel(mutex: ObjectID): NotifyLabel? = when (this) {
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
        val argsString = if (operationKind == ParkingOperationKind.Unpark) "($threadId)" else ""
        return "${operationKind}${kind.repr}${argsString}"
    }

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
        require(isRequest || isResponse || isReceive)
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

// TODO: generalize actor labels to method call/return labels?
data class ActorLabel(
    val threadId: ThreadID,
    val actorKind: ActorLabelKind,
    val actor: Actor
) : EventLabel(actorKind.labelKind())

enum class ActorLabelKind { Start, End, Span }

fun ActorLabelKind.labelKind(): LabelKind = when (this) {
    ActorLabelKind.Start -> LabelKind.Request
    ActorLabelKind.End -> LabelKind.Response
    ActorLabelKind.Span -> LabelKind.Receive
}

sealed class CoroutineLabel(
    override val kind: LabelKind,
    open val threadId: Int,
    open val actorId: Int,
    isBlocking: Boolean = false,
    unblocked: Boolean = true,
) : EventLabel(kind, isBlocking, unblocked) {

    override fun toString(): String {
        val operationKind = when (this) {
            is CoroutineSuspendLabel -> "Suspend"
            is CoroutineResumeLabel  -> "Resume"
        }
        val cancelled = if (this is CoroutineSuspendLabel && cancelled) "cancelled" else null
        return "${operationKind}${kind.repr}(${listOfNotNull(threadId, actorId, cancelled).joinToString()})"
    }

}

data class CoroutineSuspendLabel(
    override val kind: LabelKind,
    override val threadId: Int,
    override val actorId: Int,
    val cancelled: Boolean = false,
    val promptCancellation: Boolean = false,
    // TODO: should we also keep resume value and cancellation flag?
) : CoroutineLabel(
    kind = kind,
    threadId = threadId,
    actorId = actorId,
    isBlocking = true,
    unblocked = (kind != LabelKind.Request),
) {
    init {
        require(isRequest || isResponse || isReceive)
        require(promptCancellation implies isRequest)
        require(cancelled implies (isResponse || isReceive))
    }

    override fun toString(): String =
        super.toString()

}

data class CoroutineResumeLabel(
    override val threadId: Int,
    override val actorId: Int,
    // TODO: should we also keep resume value and cancellation flag?
) : CoroutineLabel(LabelKind.Send, threadId, actorId) {

    override fun toString(): String =
        super.toString()

}

data class RandomLabel(val value: Int): EventLabel(kind = LabelKind.Send)