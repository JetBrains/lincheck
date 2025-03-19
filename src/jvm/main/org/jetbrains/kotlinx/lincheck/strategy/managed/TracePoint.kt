/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.CancellationResult.*
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.kotlinx.lincheck.util.ThreadId
import kotlin.collections.map
import org.jetbrains.kotlinx.lincheck.transformation.CodeLocations

data class Trace(
    val trace: List<TracePoint>,
    val threadNames: List<String>,
) {
    fun deepCopy(): Trace {
        val copiedCallStackTraceElements = HashMap<CallStackTraceElement, CallStackTraceElement>()
        return Trace(trace.map { it.deepCopy(copiedCallStackTraceElements) }, threadNames.toList())
    }
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
 * [callStackTrace] helps to understand whether two events
 * happened in the same, nested, or disjoint methods.
 *
 * @property eventId id of the trace point, used by the Lincheck IDEA Plugin.
 * It is set only in case the plugin is enabled.
 */
sealed class TracePoint(val iThread: Int, val actorId: Int, callStackTrace: CallStackTrace, var eventId: Int = -1) {
    // This field assignment creates a copy of current callStackTrace using .toList()
    // as CallStackTrace is a mutable list and can be changed after this trace point is created.
    internal var callStackTrace = callStackTrace.toList()
    internal abstract fun toStringImpl(withLocation: Boolean): String
    override fun toString(): String = toStringImpl(withLocation = true)
    internal abstract fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint
}

internal typealias CallStackTrace = List<CallStackTraceElement>
internal fun CallStackTrace.deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): CallStackTrace =
    map { it.deepCopy(copiedCallStackTraceElements) }

internal class SwitchEventTracePoint(
    iThread: Int, actorId: Int,
    val reason: SwitchReason,
    callStackTrace: CallStackTrace
) : TracePoint(iThread, actorId, callStackTrace) {
    override fun toStringImpl(withLocation: Boolean): String {
        val reason = reason.toString()
        return "switch" + if (reason.isEmpty()) "" else " (reason: $reason)"
    }

    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        SwitchEventTracePoint(iThread, actorId, reason, callStackTrace.deepCopy(copiedCallStackTraceElements))
            .also {it.eventId = eventId}
}

/**
 * While code locations just define certain bytecode instructions,
 * code location trace points correspond to visits of these bytecode instructions.
 * [stackTraceElement] provides information about the class, the file and the position in file
 * the code location has.
 */
internal abstract class CodeLocationTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    codeLocation: Int
) : TracePoint(iThread, actorId, callStackTrace) {
    var stackTraceElement = CodeLocations.stackTrace(codeLocation)
        private set

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
        return toStringCompact() + if (withLocation) " at ${stackTraceElement.shorten()}" else ""
    }
}

internal class StateRepresentationTracePoint(
    iThread: Int, actorId: Int,
    val stateRepresentation: String,
    callStackTrace: CallStackTrace
) : TracePoint(iThread, actorId, callStackTrace) {
    override fun toStringImpl(withLocation: Boolean): String = "STATE: $stateRepresentation"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        StateRepresentationTracePoint(iThread, actorId,stateRepresentation, callStackTrace.deepCopy(copiedCallStackTraceElements))
            .also {it.eventId = eventId}
}

/**
 * This TracePoint is added only at the end of an execution when obstruction freedom is violated
 */
internal class ObstructionFreedomViolationExecutionAbortTracePoint(
    iThread: Int,
    actorId: Int,
    callStackTrace: CallStackTrace
): TracePoint(iThread, actorId, callStackTrace) {
    override fun toStringImpl(withLocation: Boolean): String = "/* An active lock was detected */"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        ObstructionFreedomViolationExecutionAbortTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements))
            .also {it.eventId = eventId}
}

internal class ReadTracePoint(
    private val ownerRepresentation: String?,
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val fieldName: String,
    codeLocation: Int,
    val isLocal: Boolean,
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, codeLocation) {
    private lateinit var valueRepresentation: String
    lateinit var valueType: String

    override fun toStringCompact(): String = StringBuilder().apply {
        if (ownerRepresentation != null) {
            append("$ownerRepresentation.$fieldName")
        } else {
            append(fieldName)
        }
        append(" ➜ $valueRepresentation")
    }.toString()

    fun initializeReadValue(value: String, type: String) {
        this.valueRepresentation = value
        this.valueType = type
        if (value == "StubClass#19") {
            Unit
        }
    }

    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        ReadTracePoint(ownerRepresentation, iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), fieldName, codeLocation, isLocal)
            .also {
                it.valueType = valueType
                it.eventId = eventId
                if (::valueRepresentation.isInitialized) it.valueRepresentation = valueRepresentation
            }
}

internal class WriteTracePoint(
    private val ownerRepresentation: String?,
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val fieldName: String,
    codeLocation: Int,
    val isLocal: Boolean,
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, codeLocation) {
    private lateinit var valueRepresentation: String
    lateinit var valueType: String

    override fun toStringCompact(): String  = StringBuilder().apply {
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
    
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        WriteTracePoint(ownerRepresentation, iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), fieldName, codeLocation, isLocal)
            .also {
                it.eventId = eventId
                it.valueType = valueType
                if (::valueRepresentation.isInitialized) it.valueRepresentation = valueRepresentation
            }
}

internal class MethodCallTracePoint(
    iThread: Int, actorId: Int,
    val className: String,
    var methodName: String,
    callStackTrace: CallStackTrace,
    codeLocation: Int,
    val isStatic: Boolean,
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, codeLocation) {
    var returnedValue: ReturnedValueResult = ReturnedValueResult.NoValue
    var thrownException: Throwable? = null
    var parameters: List<String>? = null
    var parameterTypes: List<String>? = null
    private var ownerName: String? = null

    val wasSuspended get() = (returnedValue == ReturnedValueResult.CoroutineSuspended)

    override fun toStringCompact(): String = StringBuilder().apply {
        when {
            isThreadCreation() -> appendThreadCreation()
            else -> appendDefaultMethodCall()
        }
        appendReturnedValue()
    }.toString()

    private fun StringBuilder.appendThreadCreation() {
        append("thread")
        val params = parameters?.let { getNonDefaultParametersWithName(threadFunctionInfo, it) }
            ?: emptyList()
        if (!params.isEmpty()) {
            append("(${params.joinToString(", ")})")
        }
    }

    private fun StringBuilder.appendDefaultMethodCall() {
        if (ownerName != null) append("$ownerName.")
        append("$methodName(${ parameters?.joinToString(", ") ?: "" })")
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

    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): MethodCallTracePoint =
        MethodCallTracePoint(iThread, actorId, className, methodName, callStackTrace.deepCopy(copiedCallStackTraceElements), codeLocation, isStatic)
            .also {
                it.eventId = eventId
                it.returnedValue = returnedValue
                it.thrownException = thrownException
                it.parameters = parameters
                it.ownerName = ownerName
                it.parameterTypes = parameterTypes
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
}


internal sealed interface ReturnedValueResult {
    data object NoValue: ReturnedValueResult
    data object VoidResult: ReturnedValueResult
    data object CoroutineSuspended: ReturnedValueResult
    data class ValueResult(val valueRepresentation: String, val valueType: String): ReturnedValueResult
}

internal class MonitorEnterTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    codeLocation: Int
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, codeLocation) {
    override fun toStringCompact(): String = "MONITORENTER"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        MonitorEnterTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), codeLocation)
            .also { it.eventId = eventId }
}

internal class MonitorExitTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    codeLocation: Int
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, codeLocation) {
    override fun toStringCompact(): String = "MONITOREXIT"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        MonitorExitTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), codeLocation)
            .also { it.eventId = eventId }
}

internal class WaitTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    codeLocation: Int
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, codeLocation) {
    override fun toStringCompact(): String = "WAIT"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        WaitTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), codeLocation)
            .also { it.eventId = eventId }
}

internal class NotifyTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    codeLocation: Int
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, codeLocation) {
    override fun toStringCompact(): String = "NOTIFY"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        NotifyTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), codeLocation)
            .also { it.eventId = eventId }
}

internal class ParkTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    codeLocation: Int
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, codeLocation) {
    override fun toStringCompact(): String = "PARK"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        ParkTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), codeLocation)
            .also { it.eventId = eventId }
}

internal class UnparkTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    codeLocation: Int
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, codeLocation) {
    override fun toStringCompact(): String = "UNPARK"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        UnparkTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), codeLocation)
            .also { it.eventId = eventId }
}

internal class ThreadStartTracePoint(
    iThread: Int, actorId: Int,
    val startedThreadId: Int,
    callStackTrace: CallStackTrace,
) : TracePoint(iThread, actorId, callStackTrace) {
    override fun toStringImpl(withLocation: Boolean): String = "start Thread ${startedThreadId + 1}"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        ThreadStartTracePoint(iThread, actorId, startedThreadId, callStackTrace.deepCopy(copiedCallStackTraceElements))
            .also { it.eventId = eventId }
}

internal class ThreadJoinTracePoint(
    iThread: Int, actorId: Int,
    val joinedThreadId: Int,
    callStackTrace: CallStackTrace,
) : TracePoint(iThread, actorId, callStackTrace) {

    override fun toStringImpl(withLocation: Boolean): String = "join Thread ${joinedThreadId + 1}"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        ThreadJoinTracePoint(iThread, actorId, joinedThreadId, callStackTrace.deepCopy(copiedCallStackTraceElements))
            .also { it.eventId = eventId }
}

internal class CoroutineCancellationTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
) : TracePoint(iThread, actorId, callStackTrace) {
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

    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        CoroutineCancellationTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements))
            .also {
                it.eventId = eventId
                if (::cancellationResult.isInitialized) it.cancellationResult = cancellationResult
                it.exception = exception
            }
}

/**
 * This trace point that is added to the trace between execution parts (init, parallel, post, validation).
 */
internal class SectionDelimiterTracePoint(val executionPart: ExecutionPart): TracePoint(0, -1, emptyList()) {
    override fun toStringImpl(withLocation: Boolean): String = ""
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        SectionDelimiterTracePoint(executionPart).also { it.eventId = eventId }
}

internal class SpinCycleStartTracePoint(iThread: Int, actorId: Int, callStackTrace: CallStackTrace): TracePoint(iThread, actorId, callStackTrace) {
    override fun toStringImpl(withLocation: Boolean) =  "/* The following events repeat infinitely: */"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        SpinCycleStartTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements))
            .also { it.eventId = eventId }
}

/**
 * Removes package info in the stack trace element representation.
 */
private fun StackTraceElement.shorten(): String {
    val stackTraceElement = this.toString()
    for (i in stackTraceElement.indices.reversed())
        if (stackTraceElement[i] == '/')
            return stackTraceElement.substring(i + 1 until stackTraceElement.length)
    return stackTraceElement
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
    class  ThreadJoinWait(val threadId: ThreadId)
                        : SwitchReason("waiting for Thread ${threadId + 1} to finish")

    // display switch reason
    override fun toString() = reason
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
 * @see [org.jetbrains.kotlinx.lincheck.transformation.MethodIds].
 */
internal class CallStackTraceElement(
    val id: Int,
    val tracePoint: MethodCallTracePoint,
    val instance: Any?,
    val methodInvocationId: Int
) {
    fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): CallStackTraceElement =
        copiedCallStackTraceElements.computeIfAbsent(this) {
            CallStackTraceElement(
                id,
                tracePoint.deepCopy(copiedCallStackTraceElements),
                instance,
                methodInvocationId
            )
        }
}
