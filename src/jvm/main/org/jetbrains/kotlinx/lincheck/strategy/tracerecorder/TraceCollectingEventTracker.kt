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
import org.jetbrains.kotlinx.lincheck.transformation.fieldCache
import org.jetbrains.kotlinx.lincheck.transformation.methodCache
import org.jetbrains.kotlinx.lincheck.transformation.variableCache
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
        codeLocation: Int,
        fieldId: Int
    ): Boolean  = runInsideIgnoredSection {
        val fieldDescriptor = fieldCache.get(fieldId)
        if (fieldDescriptor.isStatic) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
        }
        if (!fieldDescriptor.isStatic && obj == null) {
            // Ignore, NullPointerException will be thrown
            return false
        }


        val threadHandle = threads[Thread.currentThread()] ?: return false
        // TODO: Should we call ReadTracePoint() which is cheaper?
        val tracePoint = threadHandle.createReadFieldTracePoint(
            obj = obj,
            className = fieldDescriptor.className,
            fieldName = fieldDescriptor.fieldName,
            codeLocation = codeLocation,
            isStatic = fieldDescriptor.isStatic,
            isFinal = fieldDescriptor.isFinal,
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
        value: Any?,
        codeLocation: Int,
        fieldId: Int
    ): Boolean = runInsideIgnoredSection {
        val fieldDescriptor = fieldCache.get(fieldId)
        if (!fieldDescriptor.isStatic && obj == null) {
            // Ignore, NullPointerException will be thrown
            return false
        }

        val threadHandle = threads[Thread.currentThread()] ?: return false
        val tracePoint = threadHandle.createWriteFieldTracepoint(
            obj = obj,
            className = fieldDescriptor.className,
            fieldName = fieldDescriptor.fieldName,
            value = value,
            codeLocation = codeLocation,
            isStatic = fieldDescriptor.isStatic,
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

    override fun afterLocalRead(codeLocation: Int, variableId: Int, value: Any?) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        val variableDescriptor = variableCache[variableId]
        threadHandle.afterLocalRead(variableDescriptor.name, value)
    }

    override fun afterLocalWrite(codeLocation: Int, variableId: Int, value: Any?) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        val variableDescriptor = variableCache[variableId]
        threadHandle.afterLocalWrite(variableDescriptor.name, value)
    }

    override fun onMethodCall(
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ): Any? = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return null
        val methodDescriptor = methodCache[methodId]

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        if (receiver == null && methodSection < AnalysisSectionType.ATOMIC) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(methodDescriptor.className)
        }

        // TODO: Should we call threadHandle.createMethodCallTracePoint() which is expensive?
        val tracePoint = MethodCallTracePoint(
            iThread = threadHandle.threadId,
            actorId = methodId,
            className = methodDescriptor.className,
            methodName = methodDescriptor.methodName,
            callStackTrace = EMPTY_CALL_STACK_TRACE,
            codeLocation = codeLocation,
            isStatic = receiver == null,
            callType = MethodCallTracePoint.CallType.NORMAL,
            isSuspend = isSuspendFunction(methodDescriptor.className, methodDescriptor.methodName, params)
        )
        threadHandle.addTracepointToCurrentCall(tracePoint)
        threadHandle.pushTracepointStackFrame(tracePoint, receiver)

        // if the method has certain guarantees, enter the corresponding section
        threadHandle.enterAnalysisSection(methodSection)
        return null
    }

    override fun onMethodCallReturn(
        descriptorId: Long,
        deterministicMethodDescriptor: Any?,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?
    ): Any? = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return result
        val methodDescriptor = methodCache[methodId]

        val tracePoint = threadHandle.popTracepointStackFrame()
        // TODO: add returned value
        // tracePoint.returnedValue

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        threadHandle.leaveAnalysisSection(methodSection)
        return result
    }

    override fun onMethodCallException(
        descriptorId: Long,
        deterministicMethodDescriptor: Any?,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        t: Throwable
    ): Throwable = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return t
        val methodDescriptor = methodCache[methodId]

        val tracePoint = threadHandle.popTracepointStackFrame()
        // TODO: add returned value
        // tracePoint.returnedValue

        val methodSection = methodAnalysisSectionType(receiver, methodDescriptor.className, methodDescriptor.methodName)
        threadHandle.leaveAnalysisSection(methodSection)
        return t
    }

    override fun onInlineMethodCall(
        methodId: Int,
        codeLocation: Int,
        owner: Any?,
    ): Unit = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        val methodDescriptor = methodCache[methodId]

        val tracePoint = MethodCallTracePoint(
            iThread = threadHandle.threadId,
            actorId = 0,
            className = methodDescriptor.className,
            methodName = methodDescriptor.methodName,
            callStackTrace = EMPTY_CALL_STACK_TRACE,
            codeLocation = codeLocation,
            isStatic = false,
            callType = MethodCallTracePoint.CallType.NORMAL,
            isSuspend = false
        )
        threadHandle.addTracepointToCurrentCall(tracePoint)
        threadHandle.pushTracepointStackFrame(tracePoint, owner)
    }

    override fun onInlineMethodCallReturn(methodId: Int): Unit = runInsideIgnoredSection {
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
            for (thread in allThreads) {
                val st = thread.tracePointStackTrace
                if (st.size == 0) {
                    output.println("# Thread ${thread.threadId + 1}: Stack underflow, report bug")
                } else {
                    if (st.size > 1) {
                        output.println("# Thread ${thread.threadId + 1}: Stack is not empty, contains ${st.size} elements, report bug")
                    } else {
                        output.println("# Thread ${thread.threadId + 1}: Stack underflow, report bug")
                    }
                    printNode(output, st.first(), 0)
                }
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
}
