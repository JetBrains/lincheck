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

import org.jetbrains.kotlinx.lincheck.strategy.ThreadAnalysisHandle
import org.jetbrains.kotlinx.lincheck.strategy.managed.ShadowStackFrame
import org.jetbrains.kotlinx.lincheck.trace.TraceCollector
import org.jetbrains.kotlinx.lincheck.transformation.CodeLocations
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.util.AnalysisProfile
import org.jetbrains.kotlinx.lincheck.util.AnalysisSectionType
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
    private var indent = ""
    private var afterNewLine = true
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
        return true
    }

    override fun beforeReadArrayElement(
        array: Any,
        index: Int,
        codeLocation: Int
    ): Boolean = false

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

        // Create method call string
        val receiverName = if (receiver == null) {
            className.substring(className.lastIndexOf('.') + 1);
        } else {
            objectToString(receiver)
        }

        appendOutput(receiverName)
        appendOutput(".")
        appendOutput(methodName)
        appendOutput("(")
        var first = true
        for (p in params) {
            if (!first) {
                appendOutput(", ")
            }
            appendOutput(objectToString(p))
            first = false
        }
        appendOutput(")")
        val ste = CodeLocations.stackTrace(codeLocation)
        if (ste.fileName != null) {
            appendOutput(" at ")
            appendOutput(ste.fileName)
            appendOutput(":")
            appendOutput(ste.lineNumber)
        }
        appendNewLine()

        depth++
        indent += " "

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

        depth--
        indent = indent.substring(1)

        appendOutput("result = ")
        appendOutput(objectToString(result))
        appendNewLine()

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

        depth--
        indent = indent.substring(1)

        appendOutput("exception = ")
        appendOutput(objectToString(t))
        appendOutput(" ")
        appendOutput(t.message ?: "<no message>")
        appendNewLine()

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
    ) = runInsideIgnoredSection {
        // Create method call string
        val receiverName = if (owner == null) {
            className.substring(className.lastIndexOf('.') + 1);
        } else {
            objectToString(owner)
        }

        appendOutput(receiverName)
        appendOutput(".")
        appendOutput(methodName)
        appendOutput("()")
        appendNewLine()

        depth++
        indent += " "
    }

    override fun onInlineMethodCallReturn(className: String, methodId: Int) = runInsideIgnoredSection {
        depth--
        indent = indent.substring(1)
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

        // Method in question was called
        appendOutput(className)
        appendOutput(".")
        appendOutput(methodName)
        appendOutput("()")
        appendNewLine()
        depth = 1
        indent = " "
    }

    fun finishAndDumpTrace() {
        output.append(sb)
        output.close()
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

    private fun appendOutput(o: String) {
        if (afterNewLine) {
            sb.append(indent)
            afterNewLine = false
        }
        sb.append(o)
    }

    private fun appendOutput(o: Int) {
        if (afterNewLine) {
            sb.append(indent)
            afterNewLine = false
        }
        sb.append(o)
    }

    private fun appendNewLine() {
        sb.append("\n")
        afterNewLine = true
        // 1G
        if (sb.length > 1024 * 1024 * 1024) {
            output.append(sb)
            sb.setLength(0)
        }
    }
}
