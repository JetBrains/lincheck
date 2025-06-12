/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.tracerecorder

import org.jetbrains.kotlinx.lincheck.actor
import org.jetbrains.kotlinx.lincheck.strategy.ThreadAnalysisHandle
import org.jetbrains.kotlinx.lincheck.strategy.managed.ShadowStackFrame
import org.jetbrains.kotlinx.lincheck.trace.CallStackTrace
import org.jetbrains.kotlinx.lincheck.trace.CallStackTraceElement
import org.jetbrains.kotlinx.lincheck.trace.FlattenTraceReporter
import org.jetbrains.kotlinx.lincheck.trace.MethodCallTracePoint
import org.jetbrains.kotlinx.lincheck.trace.MethodReturnTracePoint
import org.jetbrains.kotlinx.lincheck.trace.ReadTracePoint
import org.jetbrains.kotlinx.lincheck.trace.Trace
import org.jetbrains.kotlinx.lincheck.trace.TraceCollector
import org.jetbrains.kotlinx.lincheck.trace.flattenTraceToGraph
import org.jetbrains.kotlinx.lincheck.transformation.CodeLocations
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.util.AnalysisProfile
import org.jetbrains.kotlinx.lincheck.util.AnalysisSectionType
import org.jetbrains.kotlinx.lincheck.util.isSuspendFunction
import org.jetbrains.kotlinx.lincheck.util.runInsideIgnoredSection
import sun.nio.ch.lincheck.*
import java.io.File
import java.io.PrintStream
import java.lang.invoke.CallSite
import java.util.concurrent.ConcurrentHashMap

/**
 * [TraceCollectingEventTracker] can trace all threads forked by method configured for tracing.
 * This constant turns off (if set to `true`) this feature.
 * It is turned off now, but can be turned on later.
 */
private const val TRACE_ONLY_ONE_THREAD = true

class TraceCollectingEventTracker(
    private val className: String,
    private val methodName: String,
    private val methodDesc: String,
    private val traceDumpPath: String?
) :  EventTracker {
    // We don't want to re-create this object each time we need it
    private val analysisProfile: AnalysisProfile = AnalysisProfile(false)

    // We don't use [ThreadDescriptor.eventTrackerData] because we need to list all descriptors in the end
    private val threads = ConcurrentHashMap<Thread, ThreadAnalysisHandle>()

/*
    // ID generator for [TracePoint]s
    private val currentTracePointId = atomic(0)
    // ID generator for [StackTraceElement]s
    private val currentCallId = atomic(0)
*/

    /////////////////////////////////////////////////
    // Single-threaded "Optimal" implementation
    // String builder for all data
    private val sb = StringBuilder()
    private val output: PrintStream
    private var depth = 0
    private var indent = CharArray(1024, { _ -> ' ' })
    private val simpleClassNames = HashMap<Class<*>, String>()

    init {
        check(TRACE_ONLY_ONE_THREAD) { "Multiple threads recording is not supported" }
        output = if (traceDumpPath == null) System.out else PrintStream(File(traceDumpPath))
    }

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) = Unit

    override fun beforeThreadStart() = Unit

    override fun afterThreadFinish() = Unit

    override fun threadJoin(thread: Thread?, withTimeout: Boolean) = Unit

    override fun onThreadRunException(exception: Throwable) = runInsideIgnoredSection {
        throw exception
    }

    override fun beforeLock(codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun lock(monitor: Any) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun unlock(monitor: Any, codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun beforePark(codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun park(codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun unpark(thread: Thread, codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun beforeWait(codeLocation: Int) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun wait(monitor: Any, withTimeout: Boolean) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support lock and monitor instrumentation")
    }

    override fun beforeNewObjectCreation(className: String) = runInsideIgnoredSection {
        LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
    }

    override fun afterNewObjectCreation(obj: Any) = Unit

    override fun getNextTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker): Long = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support Trace Debugger-specific instrumentation")
        return 0
    }

    override fun advanceCurrentTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker, oldId: Long) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support Trace Debugger-specific instrumentation")
    }

    override fun getCachedInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>
    ): CallSite? = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support invoke dynamic instrumentation")
        return null
    }

    override fun cacheInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Injections.HandlePojo,
        bootstrapMethodArguments: Array<out Any?>,
        callSite: CallSite
    ) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support invoke dynamic instrumentation")
    }

    override fun updateSnapshotBeforeConstructorCall(objs: Array<out Any?>) = Unit

    override fun beforeReadField(
        obj: Any?,
        className: String,
        fieldName: String,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean
    ): Boolean  = runInsideIgnoredSection {
        if (isStatic) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
        }
/*
        val threadHandle = threads[Thread.currentThread()] ?: return false
        val tracePoint = ReadTracePoint(
            ownerRepresentation = if (obj == null) null else objectToString(obj),
            iThread = threadHandle.threadId,
            actorId = 0,
            callStackTrace = EMPTY_CALL_STACK_TRACE,
            fieldName = fieldName,
            codeLocation = codeLocation,
            isLocal = false
        )
        threadHandle.traceCollector?.addTracePoint(tracePoint)
*/
        return false
    }

    override fun beforeReadArrayElement(
        array: Any,
        index: Int,
        codeLocation: Int
    ): Boolean = runInsideIgnoredSection {
/*
        val threadHandle = threads[Thread.currentThread()] ?: return false

        val tracePoint = ReadTracePoint(
            ownerRepresentation = objectToString(array) + "[" + index + "]",
            iThread = threadHandle.threadId,
            actorId = 0,
            callStackTrace = EMPTY_CALL_STACK_TRACE,
            fieldName = fieldName,
            codeLocation = codeLocation,
            isLocal = false
        )
        threadHandle.traceCollector?.addTracePoint(tracePoint)
*/

        return false
    }

    override fun afterRead(value: Any?) = Unit

    override fun beforeWriteField(
        obj: Any?,
        className: String,
        fieldName: String,
        value: Any?,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean
    ): Boolean = false

    override fun beforeWriteArrayElement(
        array: Any,
        index: Int,
        value: Any?,
        codeLocation: Int
    ): Boolean = false

    override fun afterWrite() = Unit

    override fun afterLocalRead(codeLocation: Int, name: String, value: Any?) = Unit

    override fun afterLocalWrite(codeLocation: Int, name: String, value: Any?) = Unit

    private val EMPTY_CALL_STACK_TRACE = emptyList<CallStackTraceElement>()

    override fun onMethodCall(
        className: String,
        methodName: String,
        codeLocation: Int,
        methodId: Int,
        methodDescriptor: String,
        receiver: Any?,
        params: Array<Any?>
    ): Any? = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return null

        val methodSection = methodAnalysisSectionType(receiver, className, methodName)
        if (receiver == null && methodSection < AnalysisSectionType.ATOMIC) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
        }

        val tracePoint = MethodCallTracePoint(
            iThread = threadHandle.threadId,
            actorId = 0,
            className = className,
            methodName = methodName,
            callStackTrace = EMPTY_CALL_STACK_TRACE,
            codeLocation = codeLocation,
            isStatic = receiver == null,
            callType = MethodCallTracePoint.CallType.NORMAL,
            isSuspend = isSuspendFunction(className, methodName, params)
        )
        threadHandle.traceCollector?.addTracePoint(tracePoint)

/*
        // Create method call string
        val receiverName = if (receiver == null) {
            className.substring(className.lastIndexOf('.') + 1);
        } else {
            objectToString(receiver)
        }

        appendLine {
            append(receiverName)
            append(".")
            append(methodName)
            append("(")
            var first = true
            for (p in params) {
                if (!first) {
                    append(", ")
                }
                append(objectToString(p))
                first = false
            }
            append(")")
            val ste = CodeLocations.stackTrace(codeLocation)
            if (ste.fileName != null) {
                append(" at ")
                append(ste.fileName)
                append(":")
                append(ste.lineNumber)
            }
        }
        depth++
*/

        // if the method has certain guarantees, enter the corresponding section
        threadHandle.enterAnalysisSection(methodSection)
        return null
    }

    override fun onMethodCallReturn(
        className: String,
        methodName: String,
        descriptorId: Long,
        determenisticDescriptor: Any?,
        methodId: Int,
        receiver: Any?,
        params: Array<out Any?>,
        result: Any?
    ): Any? = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return result

        val tracePoint = MethodReturnTracePoint(
            iThread = threadHandle.threadId,
            actorId = 0,
            result = result
        )
        threadHandle.traceCollector?.addTracePoint(tracePoint)

/*
        depth--
        appendLine {
            append("result = ")
            append(objectToString(result))
        }
*/

        val methodSection = methodAnalysisSectionType(receiver, className, methodName)
        threadHandle.leaveAnalysisSection(methodSection)
        return result
    }

    override fun onMethodCallException(
        className: String,
        methodName: String,
        descriptorId: Long,
        destermenisticDescriptor: Any?,
        receiver: Any?,
        params: Array<out Any?>,
        t: Throwable
    ): Throwable = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return t
        if (threadHandle.stackTrace.isEmpty()) {
            return t
        }

        val tracePoint = MethodReturnTracePoint(
            iThread = threadHandle.threadId,
            actorId = 0,
            exception = t
        )
        threadHandle.traceCollector?.addTracePoint(tracePoint)

/*
        depth--
        appendLine {
            append("exception = ")
            append(objectToString(t))
            append(" ")
            append(t.message ?: "<no message>")
        }
*/

        val methodSection = methodAnalysisSectionType(receiver, className, methodName)
        threadHandle.leaveAnalysisSection(methodSection)
        return t
    }

    override fun onInlineMethodCall(
        className: String,
        methodName: String,
        methodId: Int,
        codeLocation: Int,
        owner: Any?
    ): Unit = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return

        val tracePoint = MethodCallTracePoint(
            iThread = threadHandle.threadId,
            actorId = 0,
            className = className,
            methodName = methodName,
            callStackTrace = EMPTY_CALL_STACK_TRACE,
            codeLocation = codeLocation,
            isStatic = false,
            callType = MethodCallTracePoint.CallType.NORMAL,
            isSuspend = false
        )
        threadHandle.traceCollector?.addTracePoint(tracePoint)

/*
        // Create method call string
        val receiverName = if (owner == null) {
            className.substring(className.lastIndexOf('.') + 1);
        } else {
            objectToString(owner)
        }

        appendLine {
            append(receiverName)
            append(".")
            append(methodName)
            append("()")
        }

        depth++
*/
    }

    override fun onInlineMethodCallReturn(className: String, methodId: Int): Unit = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return

        val tracePoint = MethodReturnTracePoint(
            iThread = threadHandle.threadId,
            actorId = 0,
            result = Unit
        )
        threadHandle.traceCollector?.addTracePoint(tracePoint)
/*
        depth--
*/
    }

    override fun invokeDeterministicallyOrNull(
        descriptorId: Long,
        descriptor: Any?,
        receiver: Any?,
        params: Array<out Any?>
    ): BootstrapResult<*>? = null

    override fun getThreadLocalRandom(): InjectedRandom  = runInsideIgnoredSection {
        val msg = "Trace Recorder mode doesn't support Random calls determinism"
        System.err.println(msg)
        error(msg)
    }

    override fun randomNextInt(): Int = runInsideIgnoredSection {
        val msg = "Trace Recorder mode doesn't support Random calls determinism"
        System.err.println(msg)
        error(msg)
    }

    override fun shouldInvokeBeforeEvent(): Boolean = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support IDEA Plugin integration")
        return false
    }

    override fun beforeEvent(eventId: Int, type: String) = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support IDEA Plugin integration")
    }

    override fun getEventId(): Int = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support IDEA Plugin integration")
        return -1
    }

    override fun setLastMethodCallEventId() = runInsideIgnoredSection {
        System.err.println("Trace Recorder mode doesn't support IDEA Plugin integration")
    }

    fun enableTrace() {
        // Start tracing in this thread
        val threadHandle = ThreadAnalysisHandle(threads.size, TraceCollector())
        // Shadow stack cannot be empty
        threadHandle.shadowStack.add(ShadowStackFrame(Thread.currentThread()))
        threads[Thread.currentThread()] = threadHandle

/*
        // Method in question was called
        appendLine {
            append(className)
            append(".")
            append(methodName)
            append("()")
        }
        depth = 1
*/
    }

    fun finishAndDumpTrace() {
        val threadHandle = threads[Thread.currentThread()] ?: return

        val reporter = FlattenTraceReporter(Trace(threadHandle.traceCollector!!.trace, listOf("Thread")))
        reporter.appendTrace(output)
        output.close()

/*
        output.append(sb)
        output.close()
*/
    }

/*
    private fun addTracePoint(point: TracePoint?) {
        if (point == null) return
        val threadHandle = threads[Thread.currentThread()] ?: return
        point.eventId = currentTracePointId.getAndIncrement()
        threadHandle.traceCollector?.addTracePoint(point)
    }

    private fun isStackRecoveryFieldAccess(obj: Any?, fieldName: String?): Boolean =
        obj is Continuation<*> && (fieldName == "label" || fieldName?.startsWith("L$") == true)

    private fun addBeforeMethodCallTracePoint(
        threadHandle: ThreadAnalysisHandle,
        owner: Any?,
        codeLocation: Int,
        methodId: Int,
        className: String,
        methodName: String,
        methodParams: Array<Any?>,
        callType: MethodCallTracePoint.CallType,
    ): MethodCallTracePoint {
        val point = threadHandle.createMethodCallTracePoint(
            obj = owner,
            className = className,
            methodName = methodName,
            params = methodParams,
            codeLocation = codeLocation,
            atomicMethodDescriptor = null,
            callType = callType
        )
        val stackTraceElement = threadHandle.createCallStackTraceElement(
            obj = owner,
            methodId = methodId,
            methodParams = methodParams,
            tracePoint = point,
            callStackTraceElementId = currentCallId.getAndIncrement(),
        )
        threadHandle.pushStackFrame(stackTraceElement)
        return point
    }
*/

    private fun methodAnalysisSectionType(
        owner: Any?,
        className: String,
        methodName: String
    ): AnalysisSectionType {
        val ownerName = owner?.javaClass?.canonicalName ?: className
        // Ignore methods called on standard I/O streams
        if (owner === System.`in` || owner === System.out || owner === System.err) {
            return AnalysisSectionType.IGNORED
        }
        return analysisProfile.getAnalysisSectionFor(ownerName, methodName)
    }

    private fun objectToString(obj: Any?): String {
        return when (obj) {
            null -> "null"
            is Character -> "'$obj'"
            is String -> "\"$obj\""
            is Number -> obj.toString()
            else -> "${simpleClassNames.computeIfAbsent(obj.javaClass) { it.simpleName }}@${System.identityHashCode(obj)}"
        }
    }

    private inline fun appendLine(lineGenerator: StringBuilder.() -> Unit) {
        sb.append(indent, 0, depth)
        sb.lineGenerator()
        sb.append("\n")
        // 1G
        if (sb.length > 1024 * 1024 * 1024) {
            output.append(sb)
            sb.setLength(0)
        }
    }
}
