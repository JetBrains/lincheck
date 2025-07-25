/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.trace

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.CancellationResult.*
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.kotlinx.lincheck.strategy.managed.LincheckAnalysisAbortedError
import org.jetbrains.kotlinx.lincheck.strategy.BlockingReason
import org.jetbrains.kotlinx.lincheck.util.ThreadId
import org.jetbrains.lincheck.descriptors.CodeLocations

data class Trace(
    val trace: List<TracePoint>,
    val threadNames: List<String>,
) {
    fun deepCopy(): Trace {
        val copiedObjects = HashMap<Any, Any>()
        return Trace(trace.map { it.deepCopy(copiedObjects) }, threadNames.toList())
    }
}

private object PlaceHolder
private inline fun <reified T: Any> HashMap<Any, Any>.mapAndCast(obj: T, compute: () -> T): T {
    if (!this.containsKey(obj)) {
        this[obj] = PlaceHolder
        this[obj] = compute()
    }
    return this[obj] as T
}

private data class FunctionInfo(
    val className: String,
    val functionName: String,
    val parameterNames: List<String>,
    val defaultParameterValues: List<String>,
) {
    init { check(parameterNames.size == defaultParameterValues.size) }
}

private val threadFunctionInfo = FunctionInfo(
    className = "kotlin.concurrent.ThreadsKt",
    functionName = "thread",
    parameterNames = listOf("start", "isDaemon", "contextClassLoader", "name", "priority", "block"),
    defaultParameterValues = listOf("true", "false", "null", "null", "-1", "")
)


/**
 * Essentially, a trace is a list of trace points, which represent
 * interleaving events, such as code location passing or thread switches,
 * and store additional information that makes the trace more readable,
 * such as the current state representations.
 *
 * @property eventId id of the trace point.
 *
 * It is set only in case the plugin is enabled.
 */
sealed class TracePoint(val eventId: Int, val iThread: Int, val actorId: Int) {

    internal abstract fun toStringImpl(withLocation: Boolean): String
    override fun toString(): String = toStringImpl(withLocation = true)
    internal abstract fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint
}

//TODO turn call stack trace into List<MethodCallTracePoint> or codelocationtracepoint
internal typealias CallStackTrace = List<CallStackTraceElement>
internal fun CallStackTrace.deepCopy(copiedObjects: HashMap<Any, Any>): CallStackTrace =
    map { it.deepCopy(copiedObjects) }

internal class SwitchEventTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    val reason: SwitchReason,
    callStackTrace: CallStackTrace
) : TracePoint(eventId, iThread, actorId) {
    // This field assignment creates a copy of current callStackTrace using .toList()
    // as CallStackTrace is a mutable list and can be changed after this trace point is created.
    internal var callStackTrace = callStackTrace.toList()

    override fun toStringImpl(withLocation: Boolean): String {
        val reason = reason.toString()
        return "switch" + if (reason.isEmpty()) "" else " (reason: $reason)"
    }
    
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        SwitchEventTracePoint(eventId, iThread, actorId, reason, callStackTrace.deepCopy(copiedObjects))
    }
}

/**
 * While code locations just define certain bytecode instructions,
 * code location trace points correspond to visits of these bytecode instructions.
 * [stackTraceElement] provides information about the class, the file, and the position in file
 * the code location has.
 */
internal abstract class CodeLocationTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : TracePoint(eventId, iThread, actorId) {
    var stackTraceElement = CodeLocations.stackTrace(codeLocation)

    var codeLocation = 0
        set(value) {
            field = value
            stackTraceElement = CodeLocations.stackTrace(value)
        }

    init {
        this.codeLocation = codeLocation
    }

    protected abstract fun toStringCompact(): String
    override fun toStringImpl(withLocation: Boolean): String {
        return toStringCompact() + if (withLocation) " at ${stackTraceElement.compress()}" else ""
    }
}

internal class StateRepresentationTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    val stateRepresentation: String
) : TracePoint(eventId, iThread, actorId) {
    override fun toStringImpl(withLocation: Boolean): String = "STATE: $stateRepresentation"
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        StateRepresentationTracePoint(eventId, iThread, actorId, stateRepresentation)
    }
}

/**
 * This TracePoint is added only at the end of an execution when obstruction freedom is violated
 */
internal class ObstructionFreedomViolationExecutionAbortTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    callStackTrace: CallStackTrace
): TracePoint(eventId, iThread, actorId) {
    // This field assignment creates a copy of current callStackTrace using .toList()
    // as CallStackTrace is a mutable list and can be changed after this trace point is created.
    internal var callStackTrace = callStackTrace.toList()

    override fun toStringImpl(withLocation: Boolean): String = "/* An active lock was detected */"
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        ObstructionFreedomViolationExecutionAbortTracePoint(eventId, iThread, actorId, callStackTrace.deepCopy(copiedObjects))
    }
}

internal class ReadTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    ownerRepresentation: String?,
    fieldName: String,
    codeLocation: Int,
    val isLocal: Boolean,
    private val valueRepresentation: String,
    val valueType: String,
) : CodeLocationTracePoint(eventId, iThread, actorId, codeLocation) {

    var fieldName = fieldName
        private set
    fun updateFieldName(name: String) { fieldName = name }

    var ownerRepresentation = ownerRepresentation
        private set
    fun updateOwnerRepresentation(owner: String?) { ownerRepresentation = owner }

    override fun toStringCompact(): String = StringBuilder().apply {
        if (ownerRepresentation != null) {
            append("$ownerRepresentation.$fieldName")
        } else {
            append(fieldName)
        }
        append(" ➜ $valueRepresentation")
    }.toString()

    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        ReadTracePoint(eventId, iThread, actorId, ownerRepresentation, fieldName, codeLocation, isLocal, valueRepresentation, valueType)
    }
}

internal class WriteTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    ownerRepresentation: String?,
    fieldName: String,
    codeLocation: Int,
    val isLocal: Boolean,
) : CodeLocationTracePoint(eventId, iThread, actorId, codeLocation) {
    private lateinit var valueRepresentation: String
    lateinit var valueType: String

    var fieldName = fieldName
        private set
    fun updateFieldName(name: String) { fieldName = name }

    var ownerRepresentation = ownerRepresentation
        private set
    fun updateOwnerRepresentation(owner: String?) { ownerRepresentation = owner }

    override fun toStringCompact(): String = StringBuilder().apply {
        if (ownerRepresentation != null) {
            append("$ownerRepresentation.$fieldName")
        } else {
            append(fieldName)
        }
        append(" = $valueRepresentation")
    }.toString()

    fun initializeWrittenValue(value: String, type: String) {
        this.valueRepresentation = value
        this.valueType = type
    }
    
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        WriteTracePoint(eventId, iThread, actorId, ownerRepresentation, fieldName, codeLocation, isLocal)
            .also {
                it.valueType = valueType
                if (::valueRepresentation.isInitialized) it.valueRepresentation = valueRepresentation
            }
    }
}

internal class MethodCallTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    val className: String,
    var methodName: String,
    codeLocation: Int,
    val isStatic: Boolean,
    val callType: CallType = CallType.NORMAL,
    val isSuspend: Boolean
) : CodeLocationTracePoint(eventId, iThread, actorId, codeLocation) {
    var returnedValue: ReturnedValueResult = ReturnedValueResult.NoValue
    var thrownException: Throwable? = null
    var parameters: List<String>? = null
    var parameterTypes: List<String>? = null

    var ownerName: String? = null
        private set
    fun updateOwnerName(name: String?) { ownerName = name }

    val isRootCall get() = callType != CallType.NORMAL
    val isActor get() = callType == CallType.ACTOR
    val isThreadStart get() = callType == CallType.THREAD_RUN

    val wasSuspended get() = (returnedValue == ReturnedValueResult.CoroutineSuspended)

    override fun toStringCompact(): String = StringBuilder().apply {
        when {
            isThreadCreation() -> appendThreadCreation()
            isRootCall -> appendActor()
            else -> appendDefaultMethodCall()
        }
        appendReturnedValue()
    }.toString()

    override fun toStringImpl(withLocation: Boolean): String {
        return super.toStringImpl(withLocation && !isRootCall)
    }
    
    private fun StringBuilder.appendThreadCreation() {
        append("thread")
        val params = parameters?.let { getNonDefaultParametersWithName(threadFunctionInfo, it) }
            ?: emptyList()
        if (!params.isEmpty()) {
            append("(${params.joinToString(", ")})")
        }
    }
    
    private fun StringBuilder.appendActor() {
        append("$methodName(${ parameters?.joinToString(", ") ?: "" })")
        if (returnedValue is ReturnedValueResult.ActorResult && (returnedValue as ReturnedValueResult.ActorResult).showAtBeginningOfActor) {
            append(": ${(returnedValue as ReturnedValueResult.ActorResult).resultRepresentation}")
        }
    }
        
    
    private fun StringBuilder.appendDefaultMethodCall() {
        if (ownerName != null) append("$ownerName.")
        if (isSuspend) {
            append("$methodName(${ parameters?.dropLast(1)?.joinToString(", ") ?: "" })")
            append(" [suspendable: ${parameters?.last()}]")
        } else {
            append("$methodName(${ parameters?.joinToString(", ") ?: "" })")
        }
    }
    
    private fun StringBuilder.appendReturnedValue() {
        val returnedValue = returnedValue
        if (returnedValue is ReturnedValueResult.ValueResult) {
            append(": ${returnedValue.valueRepresentation}")
        } else if (returnedValue is ReturnedValueResult.CoroutineSuspended) {
            append(": COROUTINE_SUSPENDED")
        } else if (thrownException != null && thrownException !is LincheckAnalysisAbortedError) {
            append(": threw ${thrownException!!.javaClass.simpleName}")
        }
    }

    override fun deepCopy(copiedObjects: HashMap<Any, Any>): MethodCallTracePoint = copiedObjects.mapAndCast(this) {
        MethodCallTracePoint(eventId, iThread, actorId, className, methodName, codeLocation, isStatic, callType, isSuspend)
            .also {
                it.returnedValue = returnedValue
                it.thrownException = thrownException
                it.parameters = parameters
                it.ownerName = ownerName
                it.parameterTypes = parameterTypes
            }
    }

    fun initializeVoidReturnedValue() {
        returnedValue = ReturnedValueResult.VoidResult
    }

    fun initializeCoroutineSuspendedResult() {
        returnedValue = ReturnedValueResult.CoroutineSuspended
    }

    fun initializeReturnedValue(valueRepresentation: String, valueType: String) {
        returnedValue = ReturnedValueResult.ValueResult(valueRepresentation, valueType)
    }

    fun initializeThrownException(exception: Throwable) {
        this.thrownException = exception
    }

    fun initializeParameters(parameters: List<String>, parameterTypes: List<String>) {
        this.parameters = parameters
        this.parameterTypes = parameterTypes
    }

    fun initializeOwnerName(ownerName: String) {
        this.ownerName = ownerName
    }

    fun isThreadCreation() = 
        methodName == threadFunctionInfo.functionName && className.replace('/', '.') == threadFunctionInfo.className

    /**
     * Checks if [FunctionInfo.defaultParameterValues] differ from the provided [actualValues].
     * If so, the value is added as `name = value` with a name provided by [FunctionInfo.parameterNames].
     * Expects all lists to be of equal size.
     */
    private fun getNonDefaultParametersWithName(
        functionInfo: FunctionInfo,
        actualValues: List<String>
    ): List<String> {
        check(actualValues.size == functionInfo.parameterNames.size)
        val result = mutableListOf<String>()
        actualValues.forEachIndexed { index, currentValue ->
            if (currentValue != functionInfo.defaultParameterValues[index]) result.add("${functionInfo.parameterNames[index]} = $currentValue")
        }
        return result
    }
    
    enum class CallType {
        NORMAL,
        ACTOR,
        THREAD_RUN,
    }
}


internal sealed interface ReturnedValueResult {
    data object NoValue: ReturnedValueResult
    data object VoidResult: ReturnedValueResult
    data object CoroutineSuspended: ReturnedValueResult
    data class ValueResult(val valueRepresentation: String, val valueType: String): ReturnedValueResult
    
    // Holds any needed data to construct result lines
    data class ActorResult(
        // representation
        val resultRepresentation: String,
        
        // Needs to be shown next to the actor line as `actor(): ...`
        val showAtBeginningOfActor: Boolean = true,
        
        // Needs to be shown after last event in actor as `result: ...`
        val showAtEndOfActor: Boolean = true,
        
        // Is true if actor is hung, prevents empty actors from appearing
        val isHung: Boolean = false,
        
        // For idea plugin
        val exceptionNumber: Int = -1,
    ): ReturnedValueResult
}

internal class MonitorEnterTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : CodeLocationTracePoint(eventId, iThread, actorId, codeLocation) {
    override fun toStringCompact(): String = "MONITORENTER"
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        MonitorEnterTracePoint(eventId, iThread, actorId, codeLocation)
    }
}

internal class MonitorExitTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : CodeLocationTracePoint(eventId, iThread, actorId, codeLocation) {
    override fun toStringCompact(): String = "MONITOREXIT"
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        MonitorExitTracePoint(eventId, iThread, actorId, codeLocation)
    }
}

internal class WaitTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : CodeLocationTracePoint(eventId, iThread, actorId, codeLocation) {
    override fun toStringCompact(): String = "WAIT"
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        WaitTracePoint(eventId, iThread, actorId, codeLocation)
    }
}

internal class NotifyTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : CodeLocationTracePoint(eventId, iThread, actorId, codeLocation) {
    override fun toStringCompact(): String = "NOTIFY"
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        NotifyTracePoint(eventId, iThread, actorId, codeLocation)
    }
}

internal class ParkTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : CodeLocationTracePoint(eventId, iThread, actorId, codeLocation) {
    override fun toStringCompact(): String = "PARK"
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        ParkTracePoint(eventId, iThread, actorId, codeLocation)
    }
}

internal class UnparkTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : CodeLocationTracePoint(eventId, iThread, actorId, codeLocation) {
    override fun toStringCompact(): String = "UNPARK"
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        UnparkTracePoint(eventId, iThread, actorId, codeLocation)
    }
}

internal fun TracePoint.isThreadStart(): Boolean {
    return this is MethodCallTracePoint && this.className == "java.lang.Thread" && this.methodName == "start"
}

internal fun TracePoint.isThreadJoin(): Boolean {
    return this is MethodCallTracePoint && this.className == "java.lang.Thread" && this.methodName == "join"
}

internal class CoroutineCancellationTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
) : TracePoint(eventId, iThread, actorId) {
    private lateinit var cancellationResult: CancellationResult
    private var exception: Throwable? = null

    fun initializeCancellationResult(cancellationResult: CancellationResult) {
        this.cancellationResult = cancellationResult
    }

    fun initializeException(e: Throwable) {
        this.exception = e
    }

    override fun toStringImpl(withLocation: Boolean): String {
        if (exception != null) return "EXCEPTION WHILE CANCELLATION"
        // Do not throw exception when lateinit field is not initialized.
        if (!::cancellationResult.isInitialized) return "<cancellation result not available>"
        return when (cancellationResult) {
            CANCELLED_BEFORE_RESUMPTION -> "CANCELLED BEFORE RESUMPTION"
            CANCELLED_AFTER_RESUMPTION -> "PROMPT CANCELLED AFTER RESUMPTION"
            CANCELLATION_FAILED -> "CANCELLATION ATTEMPT FAILED"
        }
    }

    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        CoroutineCancellationTracePoint(eventId, iThread, actorId)
            .also {
                if (::cancellationResult.isInitialized) it.cancellationResult = cancellationResult
                it.exception = exception
            }
    }
}

/**
 * This trace point that is added to the trace between execution parts (init, parallel, post, validation).
 */
internal class SectionDelimiterTracePoint(
    eventId: Int,
    val executionPart: ExecutionPart
) : TracePoint(
        eventId = eventId,
        iThread = 0,
        actorId = -1,
) {
    override fun toStringImpl(withLocation: Boolean): String = ""
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        SectionDelimiterTracePoint(eventId, executionPart)
    }
}

internal class SpinCycleStartTracePoint(
    eventId: Int,
    iThread: Int,
    actorId: Int,
    callStackTrace: CallStackTrace
): TracePoint(eventId, iThread, actorId) {
    // This field assignment creates a copy of current callStackTrace using .toList()
    // as CallStackTrace is a mutable list and can be changed after this trace point is created.
    internal var callStackTrace = callStackTrace.toList()

    override fun toStringImpl(withLocation: Boolean) =  "/* The following events repeat infinitely: */"
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) { 
        SpinCycleStartTracePoint(eventId, iThread, actorId, callStackTrace.deepCopy(copiedObjects))
    }
}

internal class MethodReturnTracePoint(
    eventId: Int,
    internal val methodTracePoint: MethodCallTracePoint
): TracePoint(eventId, methodTracePoint.iThread, methodTracePoint.actorId) {
    override fun toStringImpl(withLocation: Boolean) =  "This trace point is temporary, it should not appear in the logs; method: ${methodTracePoint.methodName}"
    override fun deepCopy(copiedObjects: HashMap<Any, Any>): TracePoint = copiedObjects.mapAndCast(this) {
        MethodReturnTracePoint(eventId, methodTracePoint)
    }
}

internal sealed class SwitchReason(private val reason: String) {
    // strategy switch decision
    object StrategySwitch : SwitchReason("")

    // switch because of a thread blocking
    object MonitorWait  : SwitchReason("wait on monitor")
    object LockWait     : SwitchReason("lock is already acquired")
    object ParkWait     : SwitchReason("thread is parked")
    object ActiveLock   : SwitchReason("active lock detected")
    object Suspended    : SwitchReason("coroutine is suspended")
    class  ThreadJoinWait(threadDisplayNumber: ThreadId)
                        : SwitchReason("waiting for Thread $threadDisplayNumber to finish")

    // display switch reason
    override fun toString() = reason
}

internal fun BlockingReason?.toSwitchReason(iThreadToDisplayNumber: (Int) -> Int): SwitchReason = when (this) {
    is BlockingReason.Locked        -> SwitchReason.LockWait
    is BlockingReason.LiveLocked    -> SwitchReason.ActiveLock
    is BlockingReason.Waiting       -> SwitchReason.MonitorWait
    is BlockingReason.Parked        -> SwitchReason.ParkWait
    is BlockingReason.Suspended     -> SwitchReason.Suspended
    is BlockingReason.ThreadJoin    -> SwitchReason.ThreadJoinWait(iThreadToDisplayNumber(joinedThreadId))
    else                            -> SwitchReason.StrategySwitch
}

/**
 * Method call stack trace element info.
 * All method calls are enumerated with unique ids to distinguish different calls of the same method.
 *
 * @property id unique identifier of the call stack trace element.
 * @property tracePoint the method call trace point corresponding to this call stack element.
 * @property instance the object on which the method was invoked (null in case of static method).
 * @property methodInvocationId identifier of the method invocation;
 *   encompasses the id of the method itself and ids of its parameters (i.e., their hash codes).
 *
 * @see [org.jetbrains.kotlinx.lincheck.transformation.methodCache].
 */
internal class CallStackTraceElement(
    val id: Int,
    val tracePoint: MethodCallTracePoint,
    val instance: Any?,
    val methodInvocationId: Int
) {
    fun deepCopy(copiedObjects: HashMap<Any, Any>): CallStackTraceElement =
        copiedObjects.mapAndCast (this) {
            CallStackTraceElement(
                id,
                tracePoint.deepCopy(copiedObjects),
                instance,
                methodInvocationId
            )
        }
}
