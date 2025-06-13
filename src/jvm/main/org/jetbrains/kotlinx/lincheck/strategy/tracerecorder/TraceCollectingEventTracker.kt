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
import org.jetbrains.kotlinx.lincheck.trace.CallStackTraceElement
import org.jetbrains.kotlinx.lincheck.trace.MethodCallTracePoint
import org.jetbrains.kotlinx.lincheck.trace.TraceCollector
import org.jetbrains.kotlinx.lincheck.trace.TracePoint
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
    private val EMPTY_CALL_STACK_TRACE = emptyList<CallStackTraceElement>()
    private var indent: CharSequence = " ".repeat(1024)
    private val simpleClassNames = HashMap<Class<*>, String>()

    // We don't want to re-create this object each time we need it
    private val analysisProfile: AnalysisProfile = AnalysisProfile(false)

    // We don't use [ThreadDescriptor.eventTrackerData] because we need to list all descriptors in the end
    private val threads = ConcurrentHashMap<Thread, ThreadAnalysisHandle>()

    init {
        check(TRACE_ONLY_ONE_THREAD) { "Multiple threads recording is not supported" }
    }

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        // Create new thread handle
        val forkedThreadHandle = ThreadAnalysisHandle(threads.size, TraceCollector())
        threads[thread] = forkedThreadHandle
        // We are ready to use this
    }

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

        val threadHandle = threads[Thread.currentThread()] ?: return false
        // TODO: Should we call ReadTracePoint() which is cheaper?
        val tracePoint = threadHandle.createReadFieldTracePoint(
            obj = obj,
            className = className,
            fieldName = fieldName,
            codeLocation = codeLocation,
            isStatic = isStatic,
            isFinal = isFinal,
            actorId = 0,
            ownerRepresentation = ""
        )
        threadHandle.addTracepointToCurrentCall(tracePoint)

        return false
    }

    override fun beforeReadArrayElement(
        array: Any,
        index: Int,
        codeLocation: Int
    ): Boolean = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return false

        val tracePoint = threadHandle.createReadArrayElementTracePoint(
            array = array,
            index = index,
            codeLocation = codeLocation,
            actorId = 0,
        )
        threadHandle.addTracepointToCurrentCall(tracePoint)

        return false
    }

    override fun afterRead(value: Any?) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        threadHandle.afterRead(value)
    }

    override fun beforeWriteField(
        obj: Any?,
        className: String,
        fieldName: String,
        value: Any?,
        codeLocation: Int,
        isStatic: Boolean,
        isFinal: Boolean
    ): Boolean = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return false
        val tracePoint = threadHandle.createWriteFieldTracepoint(
            obj = obj,
            className = className,
            fieldName = fieldName,
            value = value,
            codeLocation = codeLocation,
            isStatic = isStatic,
            actorId = 0,
            ownerRepresentation = ""
        )
        threadHandle.addTracepointToCurrentCall(tracePoint)

        return false
    }

    override fun beforeWriteArrayElement(
        array: Any,
        index: Int,
        value: Any?,
        codeLocation: Int
    ): Boolean = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return false
        val tracePoint = threadHandle.createWriteArrayElementTracePoint(
            array = array,
            index = index,
            value = value,
            codeLocation = codeLocation,
            actorId = 0
        )
        threadHandle.addTracepointToCurrentCall(tracePoint)

        return false
    }

    override fun afterWrite() = Unit

    override fun afterLocalRead(codeLocation: Int, name: String, value: Any?) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        threadHandle.afterLocalRead(name, value)
    }

    override fun afterLocalWrite(codeLocation: Int, name: String, value: Any?) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        threadHandle.afterLocalWrite(name, value)
    }

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

        // TODO: Should we call threadHandle.createMethodCallTracePoint() which is expensive?
        val tracePoint = MethodCallTracePoint(
            iThread = threadHandle.threadId,
            actorId = methodId,
            className = className,
            methodName = methodName,
            callStackTrace = EMPTY_CALL_STACK_TRACE,
            codeLocation = codeLocation,
            isStatic = receiver == null,
            callType = MethodCallTracePoint.CallType.NORMAL,
            isSuspend = isSuspendFunction(className, methodName, params)
        )
        threadHandle.addTracepointToCurrentCall(tracePoint)
        threadHandle.pushTracepointStackFrame(tracePoint, receiver)

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

        val tracePoint = threadHandle.popTracepointStackFrame()
        // TODO: add returned value
        // tracePoint.returnedValue

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

        val tracePoint = threadHandle.popTracepointStackFrame()
        // TODO: add returned value
        // tracePoint.returnedValue

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
        threadHandle.addTracepointToCurrentCall(tracePoint)
        threadHandle.pushTracepointStackFrame(tracePoint, owner)
    }

    override fun onInlineMethodCallReturn(className: String, methodId: Int): Unit = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        val tracePoint = threadHandle.popTracepointStackFrame()
        // TODO: add returned value
        // tracePoint.returnedValue
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

    private var startTime = 0L

    fun enableTrace() {
        // Start tracing in this thread
        val threadHandle = ThreadAnalysisHandle(threads.size, TraceCollector())
        // Shadow stack cannot be empty
        threadHandle.shadowStack.add(ShadowStackFrame(Thread.currentThread()))
        threads[Thread.currentThread()] = threadHandle

        val tracePoint = MethodCallTracePoint(
            iThread = threadHandle.threadId,
            actorId = 0,
            className = className,
            methodName = methodName,
            callStackTrace = EMPTY_CALL_STACK_TRACE,
            codeLocation = -1,
            isStatic = false,
            callType = MethodCallTracePoint.CallType.ACTOR,
            isSuspend = false
        )
        threadHandle.pushTracepointStackFrame(tracePoint, null)

        startTime = System.currentTimeMillis();
    }

    fun finishAndDumpTrace() {
        val allThreads = mutableListOf<ThreadAnalysisHandle>()
        allThreads.addAll(threads.values)
        threads.clear()

        System.err.println("Trace record time: ${System.currentTimeMillis() - startTime}")
        startTime = System.currentTimeMillis()

        val output = if (traceDumpPath == null) {
            System.out
        } else {
            PrintStream(File(traceDumpPath).outputStream().buffered(1024*1024*1024), false)
        }
        try {
            var id = 1
            for (thread in allThreads) {
                output.println("# Thread ${id}")
                id++
                printNode(output, thread.tracePointStackTrace.first(), 0)
            }
        } finally {
            output.close()
        }

        System.err.println("Output time: ${System.currentTimeMillis() - startTime}")
    }

    private fun printNode(output: Appendable, node: TracePoint, depth: Int) {
        output.append(indent, 0, depth)
        output.append(node.toStringImpl(false))
        output.append("\n")
        if (node is MethodCallTracePoint) {
            for (c in node.getChildren()) {
                printNode(output, c, depth + 1)
            }
        }
    }

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
}
