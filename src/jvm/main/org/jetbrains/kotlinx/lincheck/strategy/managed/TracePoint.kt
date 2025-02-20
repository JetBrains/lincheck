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
import org.objectweb.asm.Type
import java.lang.Class
import kotlin.collections.map
import kotlin.reflect.jvm.kotlinFunction
import kotlin.text.replace

data class Trace(
    val trace: List<TracePoint>,
    val threadNames: List<String>,
) {
    fun deepCopy(): Trace {
        val copiedCallStackTraceElements = HashMap<CallStackTraceElement, CallStackTraceElement>()
        return Trace(trace.map { it.deepCopy(copiedCallStackTraceElements) }, threadNames.toList())
    }
}

private val threadDefaultValues = listOf("true", "false", "null", "null", "-1", "")
private const val THREAD_CLASS_NAME = "kotlin/concurrent/ThreadsKt"
internal const val THREAD_FUN_DESCRIPTOR = "(ZZLjava/lang/ClassLoader;Ljava/lang/String;ILkotlin/jvm/functions/Function0;)Ljava/lang/Thread;"


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
    var stackTraceElement: StackTraceElement
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
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    private lateinit var valueRepresentation: String

    override fun toStringCompact(): String = StringBuilder().apply {
        if (ownerRepresentation != null) {
            append("$ownerRepresentation.$fieldName")
        } else {
            append(fieldName)
        }
        append(" ➜ $valueRepresentation")
    }.toString()

    fun initializeReadValue(value: String) {
        this.valueRepresentation = value
        if (value == "StubClass#19") {
            Unit
        }
    }
    
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        ReadTracePoint(ownerRepresentation, iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), fieldName, stackTraceElement)
            .also {
                it.eventId = eventId 
                if (::valueRepresentation.isInitialized) it.valueRepresentation = valueRepresentation
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
            append("$ownerRepresentation.$fieldName")
        } else {
            append(fieldName)
        }
        append(" = $valueRepresentation")
    }.toString()

    fun initializeWrittenValue(value: String) {
        this.valueRepresentation = value
    }
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        WriteTracePoint(ownerRepresentation, iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), fieldName, stackTraceElement)
            .also {
                it.eventId = eventId 
                if (::valueRepresentation.isInitialized) it.valueRepresentation = valueRepresentation
            }
}

internal class MethodCallTracePoint(
    iThread: Int, actorId: Int,
    val className: String,
    var methodName: String,
    var descriptor: String,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    var returnedValue: ReturnedValueResult = ReturnedValueResult.NoValue
    var thrownException: Throwable? = null
    var parameters: List<String>? = null
    private var ownerName: String? = null

    val wasSuspended get() = (returnedValue == ReturnedValueResult.CoroutineSuspended)
    private val paramNames: List<String> by lazy {
        val methods = Class.forName(className.replace('/', '.')).methods
        val method = methods.firstOrNull { Type.getMethodDescriptor(it) == descriptor }
        method?.kotlinFunction?.parameters?.map { it.name ?: "" } ?: emptyList()
    }

    override fun toStringCompact(): String = StringBuilder().apply {
        // If thread creation site
        if (descriptor == THREAD_FUN_DESCRIPTOR && className == THREAD_CLASS_NAME) {
            append("thread")
            val params = parameters?.checkDefault(paramNames, threadDefaultValues) ?: emptyList()
            if (!params.isEmpty()) {
                append("(")
                append(params.joinToString(", "))
                append(")")
            }
            append(" { ... } ")
        } else {
            if (ownerName != null) append("$ownerName.")
            append("$methodName(")
            val parameters = parameters
            if (parameters != null) {
                append(parameters.joinToString(", "))
            }
            append(")")
        }
            val returnedValue = returnedValue
            if (returnedValue is ReturnedValueResult.ValueResult) {
                append(": ${returnedValue.valueRepresentation}")
            } else if (returnedValue is ReturnedValueResult.CoroutineSuspended) {
                append(": COROUTINE_SUSPENDED")
            } else if (thrownException != null && thrownException != ThreadAbortedError) {
                append(": threw ${thrownException!!.javaClass.simpleName}")
            }
        
    }.toString()
    
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): MethodCallTracePoint =
        MethodCallTracePoint(iThread, actorId, className, methodName, descriptor, callStackTrace.deepCopy(copiedCallStackTraceElements), stackTraceElement)
            .also {
                it.eventId = eventId
                it.returnedValue = returnedValue
                it.thrownException = thrownException
                it.parameters = parameters
                it.ownerName = ownerName
            }

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

private fun List<String>.checkDefault(paramNames: List<String>, defaultValues: List<String>): List<String> {
    check(this.size == paramNames.size)
    check(paramNames.size == defaultValues.size)
    val result = mutableListOf<String>()
    dropLast(1).forEachIndexed { index, currentValue -> 
        if (currentValue != defaultValues[index]) result.add("${paramNames[index]} = $currentValue")
    }
    return result
}

internal sealed interface ReturnedValueResult {
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
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        MonitorEnterTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), stackTraceElement)
            .also { it.eventId = eventId }
}

internal class MonitorExitTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "MONITOREXIT"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        MonitorExitTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), stackTraceElement)
            .also { it.eventId = eventId }
}

internal class WaitTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "WAIT"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        WaitTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), stackTraceElement)
            .also { it.eventId = eventId }
}

internal class NotifyTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "NOTIFY"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        NotifyTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), stackTraceElement)
            .also { it.eventId = eventId }
}

internal class ParkTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "PARK"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        ParkTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), stackTraceElement)
            .also { it.eventId = eventId }
}

internal class UnparkTracePoint(
    iThread: Int, actorId: Int,
    callStackTrace: CallStackTrace,
    stackTraceElement: StackTraceElement
) : CodeLocationTracePoint(iThread, actorId, callStackTrace, stackTraceElement) {
    override fun toStringCompact(): String = "UNPARK"
    override fun deepCopy(copiedCallStackTraceElements: HashMap<CallStackTraceElement, CallStackTraceElement>): TracePoint =
        UnparkTracePoint(iThread, actorId, callStackTrace.deepCopy(copiedCallStackTraceElements), stackTraceElement)
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
