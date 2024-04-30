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
import java.math.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

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
    protected val stackTraceElement: StackTraceElement
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
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val fieldName: String?,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    private var value: Any? = null

    override fun toStringCompact(): String = StringBuilder().apply {
        if (fieldName != null)
            append("$fieldName.")
        append("READ")
        append(": ${adornedStringRepresentation(value)}")
    }.toString()

    fun initializeReadValue(value: Any?) {
        this.value = value
    }
}

internal class WriteTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val fieldName: String?,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    private var value: Any? = null

    override fun toStringCompact(): String  = StringBuilder().apply {
        if (fieldName != null)
            append("$fieldName.")
        append("WRITE(")
        append(adornedStringRepresentation(value))
        append(")")
    }.toString()

    fun initializeWrittenValue(value: Any?) {
        this.value = value
    }
}

internal class MethodCallTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    private val methodName: String,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    private var returnedValue: Any? = NO_VALUE
    private var thrownException: Throwable? = null
    private var parameters: Array<Any?>? = null
    private var ownerName: String? = null

    val wasSuspended get() = returnedValue === COROUTINE_SUSPENDED

    override fun toStringCompact(): String = StringBuilder().apply {
        if (ownerName != null)
            append("$ownerName.")
        append("$methodName(")
        if (parameters != null)
            append(parameters!!.joinToString(",", transform = ::adornedStringRepresentation))
        append(")")
        if (returnedValue != NO_VALUE && returnedValue != VoidResult)
            append(": ${adornedStringRepresentation(returnedValue)}")
        else if (thrownException != null && thrownException != ForcibleExecutionFinishError)
            append(": threw ${thrownException!!.javaClass.simpleName}")
    }.toString()

    fun initializeReturnedValue(value: Any?) {
        this.returnedValue = value
    }

    fun initializeThrownException(exception: Throwable) {
        this.thrownException = exception
    }

    fun initializeParameters(parameters: Array<Any?>) {
        this.parameters = parameters
    }

    fun initializeOwnerName(ownerName: String?) {
        this.ownerName = ownerName
    }
}
private val NO_VALUE = Any()

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

private fun adornedStringRepresentation(any: Any?): String {
    // Primitive types (and several others) are immutable and
    // have trivial `toString` implementation, which is used here.
    if (any == null || any.javaClass.isImmutableWithNiceToString)
        return any.toString()
    // For enum types, we can always display their name.
    if (any.javaClass.isEnum) {
        return (any as Enum<*>).name
    }
    // simplified representation for Continuations
    // (we usually do not really care about details).
    if (any is Continuation<*>)
        return "<cont>"
    // Instead of java.util.HashMap$Node@3e2a56 show Node@1.
    // It is better not to use `toString` in general since
    // we usually care about references to certain objects,
    // not about the content inside them.
    return getObjectName(any)
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
 * Method call info.
 *
 * All methods calls are enumerated to make it possible to distinguish different calls of the same method.
 * Suspended method calls have the same [identifier] before and after suspension, but different [call] points.
 */
internal class CallStackTraceElement(val call: MethodCallTracePoint, val identifier: Int)

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private val Class<out Any>?.isImmutableWithNiceToString get() = this?.canonicalName in
    listOf(
        java.lang.Integer::class.java,
        java.lang.Long::class.java,
        java.lang.Short::class.java,
        java.lang.Double::class.java,
        java.lang.Float::class.java,
        java.lang.Character::class.java,
        java.lang.Byte::class.java,
        java.lang.Boolean::class.java,
        java.lang.String::class.java,
        BigInteger::class.java,
        BigDecimal::class.java,
        kotlinx.coroutines.internal.Symbol::class.java,
    ).map { it.canonicalName } +
    listOf(
        "java.util.Collections.SingletonList",
        "java.util.Collections.SingletonMap",
        "java.util.Collections.SingletonSet"
    )