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

data class Trace(val trace: List<TracePoint>)

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
    internal val callStackTrace = callStackTrace.toList()
    internal abstract fun toStringImpl(withLocation: Boolean): String
    override fun toString(): String = toStringImpl(withLocation = true)
}

internal typealias CallStackTrace = List<CallStackTraceElement>

internal class SwitchEventTracePoint(
    iThread: Int, actorId: Int,
    val reason: SwitchReason,
    callStackTrace: CallStackTrace
) : TracePoint(iThread, actorId, callStackTrace) {
    override fun toStringImpl(withLocation: Boolean): String {
        val reason = reason.toString()
        return "switch" + if (reason.isEmpty()) "" else " (reason: $reason)"
    }
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
    val stackTraceElement: StackTraceElement
) : TracePoint(iThread, actorId, callStackTrace) {

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
}

internal class ReadTracePoint(
    private val ownerRepresentation: String?,
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val fieldName: String,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    private lateinit var valueRepresentation: String

    override fun toStringCompact(): String = StringBuilder().apply {
        if (ownerRepresentation != null) {
            append("$ownerRepresentation.$fieldName.")
        } else {
            append("$fieldName.")
        }
        append("READ")
        append(": $valueRepresentation")
    }.toString()

    fun initializeReadValue(value: String) {
        this.valueRepresentation = value
        if (value == "StubClass#19") {
            Unit
        }
    }
}

internal class WriteTracePoint(
    private val ownerRepresentation: String?,
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val fieldName: String,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    private lateinit var valueRepresentation: String

    override fun toStringCompact(): String  = StringBuilder().apply {
        if (ownerRepresentation != null) {
            append("$ownerRepresentation.$fieldName.")
        } else {
            append("$fieldName.")
        }
        append("WRITE(")
        append(valueRepresentation)
        append(")")
    }.toString()

    fun initializeWrittenValue(value: String) {
        this.valueRepresentation = value
    }
}

internal class MethodCallTracePoint(
    iThread: Int, actorId: Int,
    private val className: String,
    private val methodName: String,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    private var returnedValue: ReturnedValueResult = ReturnedValueResult.NoValue
    private var thrownException: Throwable? = null
    private var parameters: List<String>? = null
    private var ownerName: String? = null

    val wasSuspended get() = (returnedValue == ReturnedValueResult.CoroutineSuspended)

    override fun toStringCompact(): String = StringBuilder().apply {
        if (ownerName != null)
            append("$ownerName.")
        append("$methodName(")
        val parameters = parameters
        if (parameters != null) {
            append(parameters.joinToString(","))
        }
        append(")")
        val returnedValue = returnedValue
        if (returnedValue is ReturnedValueResult.ValueResult) {
            append(": ${returnedValue.valueRepresentation}")
        } else if (returnedValue is ReturnedValueResult.CoroutineSuspended) {
            append(": COROUTINE_SUSPENDED")
        } else if (thrownException != null && thrownException != ForcibleExecutionFinishError) {
            append(": threw ${thrownException!!.javaClass.simpleName}")
        }
    }.toString()

    fun initializeVoidReturnedValue() {
        returnedValue = ReturnedValueResult.VoidResult
    }

    fun initializeCoroutineSuspendedResult() {
        returnedValue = ReturnedValueResult.CoroutineSuspended
    }

    fun initializeReturnedValue(valueRepresentation: String) {
        returnedValue = ReturnedValueResult.ValueResult(valueRepresentation)
    }

    fun initializeThrownException(exception: Throwable) {
        this.thrownException = exception
    }

    fun initializeParameters(parameters: List<String>) {
        this.parameters = parameters
    }

    fun initializeOwnerName(ownerName: String) {
        this.ownerName = ownerName
    }
}

private sealed interface ReturnedValueResult {
    data object NoValue: ReturnedValueResult
    data object VoidResult: ReturnedValueResult
    data object CoroutineSuspended: ReturnedValueResult
    data class ValueResult(val valueRepresentation: String): ReturnedValueResult
}

internal class MonitorEnterTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "MONITORENTER"
}

internal class MonitorExitTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "MONITOREXIT"
}

internal class WaitTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "WAIT"
}

internal class NotifyTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "NOTIFY"
}

internal class ParkTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "PARK"
}

internal class UnparkTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "UNPARK"
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
}

/**
 * This trace point that is added to the trace between execution parts (init, parallel, post, validation).
 */
internal class SectionDelimiterTracePoint(val executionPart: ExecutionPart): TracePoint(0, -1, emptyList()) {
    override fun toStringImpl(withLocation: Boolean): String = ""
}

internal class SpinCycleStartTracePoint(iThread: Int, actorId: Int, callStackTrace: CallStackTrace): TracePoint(iThread, actorId, callStackTrace) {
    override fun toStringImpl(withLocation: Boolean) =  "/* The following events repeat infinitely: */"
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

internal enum class SwitchReason(private val reason: String) {
    MONITOR_WAIT("wait on monitor"),
    LOCK_WAIT("lock is already acquired"),
    ACTIVE_LOCK("active lock detected"),
    SUSPENDED("coroutine is suspended"),
    STRATEGY_SWITCH("");

    override fun toString() = reason
}

/**
 * Method call stack trace element info.
 *
 * All method calls are enumerated to make it possible to distinguish different calls of the same method.
 * Suspended method calls have the same [suspensionId] before and after suspension, but different [tracePoint].
 *
 * @property tracePoint the method call trace point corresponding to this call stack element.
 * @property suspensionId for `suspend` methods, stores the method identifier
 *   to match the method call before suspension and after resumption.
 * @property methodInvocationId identifier of the method invocation;
 *   encompasses the id of the method itself and ids of its parameters (i.e., their hash codes).
 *
 * @see [org.jetbrains.kotlinx.lincheck.transformation.MethodIds].
 */
internal class CallStackTraceElement(
    val tracePoint: MethodCallTracePoint,
    val suspensionId: Int,
    val methodInvocationId: Int
)
