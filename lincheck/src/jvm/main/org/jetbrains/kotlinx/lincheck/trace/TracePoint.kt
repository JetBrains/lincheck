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
import org.jetbrains.lincheck.util.LincheckAnalysisAbortedError
import org.jetbrains.kotlinx.lincheck.strategy.BlockingReason
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.jvm.agent.toSimpleClassName
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.UNKNOWN_CODE_LOCATION_ID

data class Trace(
    val trace: List<TracePoint>,
    val threadNames: List<String>,
) {
    fun copy(): Trace = Trace(trace.map { it.copy() }, threadNames.toList())
}

/**
 * Essentially, a trace is a list of trace points, which represent
 * interleaving events, such as code location passing or thread switches,
 * and store additional information that makes the trace more readable,
 * such as the current state representations.
 */
sealed class TracePoint(
    val context: TraceContext,
    val eventId: Int,
    val iThread: Int,
    val actorId: Int,
    codeLocation: Int = UNKNOWN_CODE_LOCATION_ID,
) {
    internal var stackTraceElement = context.stackTrace(codeLocation)

    internal var codeLocation = codeLocation
        set(value) {
            field = value
            stackTraceElement = context.stackTrace(value)
        }

    internal fun locationSuffix(): String =
        if (codeLocation == UNKNOWN_CODE_LOCATION_ID) "" else " at ${stackTraceElement.compress()}"

    internal abstract fun toString(withValues: Boolean): String

    internal open fun toString(withLocation: Boolean, withValues: Boolean): String =
        toString(withValues) + if (withLocation) locationSuffix() else ""

    override fun toString(): String = toString(withLocation = true, withValues = true)

    internal abstract fun copy(): TracePoint
}

internal class SwitchEventTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    val reason: SwitchReason,
) : TracePoint(context, eventId, iThread, actorId) {

    override fun toString(withValues: Boolean): String {
        val reason = reason.toString()
        return "switch" + if (reason.isEmpty()) "" else " (reason: $reason)"
    }
    
    override fun copy(): TracePoint =
        SwitchEventTracePoint(context, eventId, iThread, actorId, reason)
}

internal class ReadTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    ownerRepresentation: String?,
    fieldName: String,
    codeLocation: Int,
    val isLocal: Boolean,
    private val valueRepresentation: String,
    val valueType: String,
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    var fieldName = fieldName
        private set

    fun updateFieldName(name: String) { fieldName = name }

    var ownerRepresentation = ownerRepresentation
        private set

    fun updateOwnerRepresentation(owner: String?) { ownerRepresentation = owner }

    override fun toString(withValues: Boolean): String = StringBuilder().apply {
        if (ownerRepresentation != null) {
            append("$ownerRepresentation.$fieldName")
        } else {
            append(fieldName)
        }
        if (withValues) {
            append(" ➜ $valueRepresentation")
        }
    }.toString()

    override fun copy(): TracePoint =
        ReadTracePoint(context, eventId, iThread, actorId,
            ownerRepresentation, fieldName, codeLocation, isLocal,
            valueRepresentation, valueType,
        )
}

internal class WriteTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    ownerRepresentation: String?,
    fieldName: String,
    codeLocation: Int,
    val isLocal: Boolean,
    private val valueRepresentation: String,
    val valueType: String,
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    var fieldName = fieldName
        private set

    fun updateFieldName(name: String) { fieldName = name }

    var ownerRepresentation = ownerRepresentation
        private set

    fun updateOwnerRepresentation(owner: String?) { ownerRepresentation = owner }

    override fun toString(withValues: Boolean): String = StringBuilder().apply {
        if (ownerRepresentation != null) {
            append("$ownerRepresentation.$fieldName")
        } else {
            append(fieldName)
        }
        if(withValues) {
            append(" = $valueRepresentation")
        }
    }.toString()

    override fun copy(): TracePoint =
        WriteTracePoint(
            context, eventId, iThread, actorId, ownerRepresentation, fieldName, codeLocation,
            isLocal, valueRepresentation, valueType,
        ).also { it.stackTraceElement = stackTraceElement }
}

internal class MethodCallTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    val className: String,
    var methodName: String,
    codeLocation: Int,
    val isStatic: Boolean,
    val callType: CallType = CallType.NORMAL,
    val isSuspend: Boolean,
    var parameters: List<String>,
    val parameterTypes: List<String>,
    ownerName: String? = null,
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    private var resultSet = false

    var returnedValue: LincheckResult? = NoResult
        private set

    var returnValueType: String = ""
        private set

    var exceptionNumber: Int = -1

    var ownerName = ownerName
        private set

    fun updateOwnerName(name: String?) { ownerName = name }

    val thrownException: Throwable? get() = (returnedValue as? ExceptionResult)?.throwable

    val isRootCall get() = callType != CallType.NORMAL
    val isActor get() = callType == CallType.ACTOR
    val isThreadStart get() = callType == CallType.THREAD_RUN

    val wasSuspended get() = (returnedValue == SuspendedResult)

    override fun toString(withValues: Boolean): String = StringBuilder().apply {
        when {
            isThreadCreation() -> appendThreadCreation()
            isRootCall -> appendActor()
            else -> appendDefaultMethodCall()
        }
        if (!isRootCall && withValues) appendReturnedValue()
    }.toString()

    override fun toString(withLocation: Boolean, withValues: Boolean): String {
        return super.toString(withLocation && !isRootCall, withValues)
    }
    
    private fun StringBuilder.appendThreadCreation() {
        append("thread")
        val params = getNonDefaultParametersWithName(threadFunctionInfo, parameters)
        if (params.isNotEmpty()) {
            append("(${params.joinToString(", ")})")
        }
    }
    
    private fun StringBuilder.appendActor() {
        append("$methodName(${parameters.joinToString(", ")})")
        if (returnedValue.showAtMethodCallBeginning() || (returnedValue.isNoResult() && isActor)) {
            append(": ${returnedValue.resultRepresentation(exceptionNumber)}")
        }
    }

    private fun StringBuilder.appendDefaultMethodCall() {
        // For constructor calls (`<init>`) `ownerName` resolves to `null` whenever the
        // receiver is the current `this` — i.e. for `super()` / `this()` delegation calls
        // inside a constructor body. Fall back to the class name being constructed so the
        // trace makes the constructor chain explicit (e.g. `ConstructorBase.<init>()`
        // instead of a bare `<init>()`).
        val displayOwner = ownerName ?: className.takeIf { methodName == "<init>" }?.toSimpleClassName()
        if (displayOwner != null) append("$displayOwner.")
        if (isSuspend) {
            append("$methodName(${parameters.dropLast(1).joinToString(", ")})")
            append(" [suspendable: ${parameters.last()}]")
        } else {
            append("$methodName(${parameters.joinToString(", ")})")
        }
    }
    
    private fun StringBuilder.appendReturnedValue() {
        when (val result = returnedValue) {
            is ValueResult -> append(": ${result.value}")
            SuspendedResult -> append(": COROUTINE_SUSPENDED")
            is ExceptionResult -> append(": threw $result")
            else -> Unit
        }
    }

    override fun copy(): MethodCallTracePoint =
        MethodCallTracePoint(
            context, eventId, iThread, actorId, className, methodName, codeLocation,
            isStatic, callType, isSuspend, parameters, parameterTypes, ownerName,
        ).also {
            it.stackTraceElement = stackTraceElement
            if (resultSet) {
                it.setResult(returnedValue, returnValueType)
            }
            it.exceptionNumber = exceptionNumber
        }

    /**
     * Updates the reporting copy of a suspended call with the outcome of its resumption.
     */
    fun updateResultAfterResumption(invocation: MethodCallTracePoint) {
        check(wasSuspended)
        returnedValue = invocation.returnedValue
        returnValueType = invocation.returnValueType
        exceptionNumber = invocation.exceptionNumber
    }

    private fun setResult(result: LincheckResult?, valueType: String = "") {
        check(!resultSet) { "Method call result was already set" }
        resultSet = true
        returnedValue = result
        returnValueType = valueType
    }

    fun setVoidReturnedValue() = setResult(VoidResult)

    fun setCoroutineSuspendedResult() = setResult(SuspendedResult)

    fun setReturnedValue(valueRepresentation: String, valueType: String) =
        setResult(ValueResult(valueRepresentation), valueType)

    fun setThrownException(exception: Throwable) = setResult(ExceptionResult(exception))

    fun setActorResult(result: LincheckResult?) {
        check(isActor)
        setResult(when (result) {
            is ExceptionResult -> result.takeUnless { it.throwable is LincheckAnalysisAbortedError }
            // A terminal actor suspension is reported as an actor outcome.
            SuspendedResult, is ValueResult -> ValueResult(result.toString())
            else -> result
        })
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
            if (currentValue != functionInfo.defaultParameterValues[index]) {
                result.add("${functionInfo.parameterNames[index]} = $currentValue")
            }
        }
        return result
    }
    
    enum class CallType {
        NORMAL,
        ACTOR,
        THREAD_RUN,
    }
}

internal fun LincheckResult?.resultRepresentation(exceptionNumber: Int = -1): String = when (this) {
    null -> "<hung>"
    SuspendedResult -> "<suspended>"
    is ExceptionResult -> "$this #$exceptionNumber"
    VoidResult, is ValueResult, CancelledResult, is ResumedResult -> toString()
}

internal fun LincheckResult?.showAtMethodCallBeginning(): Boolean = when (this) {
    null, VoidResult, SuspendedResult -> false
    is ValueResult, is ExceptionResult, CancelledResult, is ResumedResult -> true
}

internal fun LincheckResult?.showAtMethodCallEnd(): Boolean = when (this) {
    null -> false
    VoidResult, SuspendedResult, is ValueResult, is ExceptionResult, CancelledResult, is ResumedResult -> true
}

internal class MonitorEnterTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    override fun toString(withValues: Boolean): String = "MONITORENTER"

    override fun copy(): TracePoint =
        MonitorEnterTracePoint(context, eventId, iThread, actorId, codeLocation)
}

internal class MonitorExitTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    override fun toString(withValues: Boolean): String = "MONITOREXIT"

    override fun copy(): TracePoint =
        MonitorExitTracePoint(context, eventId, iThread, actorId, codeLocation)
}

internal class WaitTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    override fun toString(withValues: Boolean): String = "WAIT"

    override fun copy(): TracePoint =
        WaitTracePoint(context, eventId, iThread, actorId, codeLocation)
}

internal class NotifyTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    override fun toString(withValues: Boolean): String = "NOTIFY"

    override fun copy(): TracePoint =
        NotifyTracePoint(context, eventId, iThread, actorId, codeLocation)
}

internal class ParkTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    override fun toString(withValues: Boolean): String = "PARK"

    override fun copy(): TracePoint =
        ParkTracePoint(context, eventId, iThread, actorId, codeLocation)
}

internal class UnparkTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    override fun toString(withValues: Boolean): String = "UNPARK"

    override fun copy(): TracePoint =
        UnparkTracePoint(context, eventId, iThread, actorId, codeLocation)
}

internal fun TracePoint.isThreadStart(): Boolean {
    return this is MethodCallTracePoint && this.className == "java.lang.Thread" && this.methodName == "start"
}

internal fun TracePoint.isThreadJoin(): Boolean {
    return this is MethodCallTracePoint && this.className == "java.lang.Thread" && this.methodName == "join"
}

internal class CoroutineCancellationTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
) : TracePoint(context, eventId, iThread, actorId) {
    private lateinit var cancellationResult: CancellationResult
    private var exception: Throwable? = null

    fun initializeCancellationResult(cancellationResult: CancellationResult) {
        this.cancellationResult = cancellationResult
    }

    fun initializeException(e: Throwable) {
        this.exception = e
    }

    override fun toString(withValues: Boolean): String {
        if (exception != null) return "EXCEPTION WHILE CANCELLATION"
        // Do not throw exception when lateinit field is not initialized.
        if (!::cancellationResult.isInitialized) return "<cancellation result not available>"
        return when (cancellationResult) {
            CANCELLED_BEFORE_RESUMPTION -> "CANCELLED BEFORE RESUMPTION"
            CANCELLED_AFTER_RESUMPTION -> "PROMPT CANCELLED AFTER RESUMPTION"
            CANCELLATION_FAILED -> "CANCELLATION ATTEMPT FAILED"
        }
    }

    override fun copy(): TracePoint =
        CoroutineCancellationTracePoint(context, eventId, iThread, actorId)
            .also {
                if (::cancellationResult.isInitialized) it.cancellationResult = cancellationResult
                it.exception = exception
            }
}

/**
 * This trace point that is added to the trace between execution parts (init, parallel, post, validation).
 */
internal class SectionDelimiterTracePoint(
    context: TraceContext,
    eventId: Int,
    val executionPart: ExecutionPart
) : TracePoint(
        context = context,
        eventId = eventId,
        iThread = 0,
        actorId = -1,
) {
    override fun toString(withValues: Boolean): String = ""
    override fun copy(): TracePoint =
        SectionDelimiterTracePoint(context, eventId, executionPart)
}

internal class MethodReturnTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    val callEventId: Int,
) : TracePoint(context, eventId, iThread, actorId) {
    constructor(context: TraceContext, eventId: Int, call: MethodCallTracePoint) :
        this(context, eventId, call.iThread, call.actorId, call.eventId)

    override fun toString(withValues: Boolean) =
        "Method return for call event $callEventId"

    override fun copy(): TracePoint =
        MethodReturnTracePoint(context, eventId, iThread, actorId, callEventId)
}

internal class LoopStartTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int,
    val loopId: Int,
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    override fun toString(withValues: Boolean): String = "loop"

    override fun copy(): TracePoint =
        LoopStartTracePoint(context, eventId, iThread, actorId, codeLocation, loopId)
}

internal class LoopIterationTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int,
    val loopId: Int,
    val iteration: Int,
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    override fun toString(withValues: Boolean): String = "<iteration $iteration>"

    override fun copy(): TracePoint =
        LoopIterationTracePoint(context, eventId, iThread, actorId, codeLocation, loopId, iteration)
}

internal class LoopEndTracePoint(
    context: TraceContext,
    eventId: Int,
    iThread: Int,
    actorId: Int,
    codeLocation: Int,
    val loopId: Int,
) : TracePoint(context, eventId, iThread, actorId, codeLocation) {

    override fun toString(withValues: Boolean): String = "loop end"

    override fun copy(): TracePoint =
        LoopEndTracePoint(context, eventId, iThread, actorId, codeLocation, loopId)
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
