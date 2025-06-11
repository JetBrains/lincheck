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

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.flattenedTraceGraphToCSV
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.kotlinx.lincheck.strategy.ThreadAnalysisHandle
import org.jetbrains.kotlinx.lincheck.strategy.managed.ShadowStackFrame
import org.jetbrains.kotlinx.lincheck.strategy.managed.UNKNOWN_CODE_LOCATION
import org.jetbrains.kotlinx.lincheck.trace.*
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.transformation.MethodIds
import org.jetbrains.kotlinx.lincheck.util.AnalysisProfile
import org.jetbrains.kotlinx.lincheck.util.AnalysisSectionType
import org.jetbrains.kotlinx.lincheck.util.runInsideIgnoredSection
import org.objectweb.asm.commons.Method.getMethod
import sun.nio.ch.lincheck.*
import java.io.File
import java.io.PrintStream
import java.lang.invoke.CallSite
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation

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
    // ID generator for [TracePoint]s
    private val currentTracePointId = atomic(0)
    // ID generator for [StackTraceElement]s
    private val currentCallId = atomic(0)

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) = runInsideIgnoredSection {
        // Don't init new threads forked from initial one if it is not enabled
        if (TRACE_ONLY_ONE_THREAD) {
            return
        }

        val threadHandle = threads[Thread.currentThread()] ?: return
        val forkedThreadHandle = ThreadAnalysisHandle(threads.size, TraceCollector())
        threads[thread] = forkedThreadHandle
        addTracePoint(ThreadStartTracePoint(
            iThread = threadHandle.threadId,
            actorId = 0,
            startedThreadDisplayNumber = forkedThreadHandle.threadId,
            callStackTrace = forkedThreadHandle.stackTrace
        ))
    }

    override fun beforeThreadStart() = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return

        val methodDescriptor = getMethod("void run()").descriptor
        addTracePoint(addBeforeMethodCallTracePoint(
            threadHandle = threadHandle,
            owner = null,
            className = "java.lang.Thread",
            methodName = "run",
            codeLocation = UNKNOWN_CODE_LOCATION,
            methodId = MethodIds.getMethodId("java.lang.Thread", "run", methodDescriptor),
            methodParams = emptyArray(),
            callType = MethodCallTracePoint.CallType.THREAD_RUN
        ))
    }

    override fun afterThreadFinish() = Unit

    override fun threadJoin(thread: Thread?, withTimeout: Boolean) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        addTracePoint(ThreadJoinTracePoint(
            iThread = threadHandle.threadId,
            actorId = 0,
            joinedThreadDisplayNumber = threads[thread]?.threadId ?: -1,
            callStackTrace = threadHandle.stackTrace,
        ))
    }

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
        threadHandle.beforeReadField(obj, className, fieldName, codeLocation, isStatic, isFinal)

        if (isFinal || isStackRecoveryFieldAccess(obj, fieldName)) {
            return false
        }

        val point = threadHandle.createReadFieldTracePoint(
            obj = obj,
            className = className,
            fieldName = fieldName,
            codeLocation = codeLocation,
            isStatic = isStatic,
            isFinal = false
        )
        addTracePoint(point)
        return true
    }

    override fun beforeReadArrayElement(
        array: Any,
        index: Int,
        codeLocation: Int
    ): Boolean = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return false
        val point = threadHandle.createReadArrayElementTracePoint(
            array = array,
            index = index,
            codeLocation = codeLocation
        )
        addTracePoint(point)
        return true
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
        if (isFinal || isStackRecoveryFieldAccess(obj, fieldName)) {
            return false
        }

        val threadHandle = threads[Thread.currentThread()] ?: return false
        val point = threadHandle.createWriteFieldTracepoint(
            obj = obj,
            className = className,
            fieldName = fieldName,
            value = value,
            codeLocation = codeLocation,
            isStatic = isStatic
        )
        addTracePoint(point)
        return true
    }

    override fun beforeWriteArrayElement(
        array: Any,
        index: Int,
        value: Any?,
        codeLocation: Int
    ): Boolean = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return false
        val point = threadHandle.createWriteArrayElementTracePoint(
            array = array,
            index = index,
            value = value,
            codeLocation = codeLocation
        )
        addTracePoint(point)
        return true
    }

    // TODO: do we need StateRepresentationTracePoint here?
    override fun afterWrite() = Unit

    override fun afterLocalRead(codeLocation: Int, name: String, value: Any?) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        threadHandle.afterLocalRead(name, value)
    }

    override fun afterLocalWrite(codeLocation: Int, name: String, value: Any?) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return
        threadHandle.afterLocalRead(name, value)
    }

    override fun onMethodCall(
        className: String,
        methodName: String,
        codeLocation: Int,
        methodId: Int,
        methodSignature: MethodSignature?,
        receiver: Any?,
        params: Array<Any?>
    ): Any? = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return null

        val methodSection = methodAnalysisSectionType(receiver, className, methodName)

        if (receiver == null && methodSection < AnalysisSectionType.ATOMIC) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
        }

        addBeforeMethodCallTracePoint(
            threadHandle = threadHandle,
            owner = receiver,
            codeLocation = codeLocation,
            methodId = methodId,
            className = className,
            methodName = methodName,
            methodParams = params,
            callType = MethodCallTracePoint.CallType.NORMAL,
        )

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
        if (threadHandle.stackTrace.isEmpty()) {
            return result
        }
        threadHandle.setMethodCallTracePointResult(result)
        threadHandle.popStackFrame()
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
        threadHandle.setMethodCallTracePointExceptionResult(t)
        threadHandle.popStackFrame()
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
        val threadHandle = threads[Thread.currentThread()] ?: return@runInsideIgnoredSection
        addBeforeMethodCallTracePoint(
            threadHandle = threadHandle,
            owner = owner,
            codeLocation = codeLocation,
            methodId = methodId,
            className = className,
            methodName = methodName,
            methodParams = emptyArray(),
            callType = MethodCallTracePoint.CallType.NORMAL,
        )
    }

    override fun onInlineMethodCallReturn(className: String, methodId: Int) = runInsideIgnoredSection {
        val threadHandle = threads[Thread.currentThread()] ?: return@runInsideIgnoredSection
        if (threadHandle.stackTrace.isEmpty()) {
            return
        }
        threadHandle.setMethodCallTracePointResult(Unit)
        threadHandle.popStackFrame()
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

        // Section start not to confuse trace post-processor
        addTracePoint(SectionDelimiterTracePoint(ExecutionPart.PARALLEL))

        // Method in question was called
        addTracePoint(addBeforeMethodCallTracePoint(
            threadHandle = threadHandle,
            owner = null,
            className = className,
            methodName = methodName,
            codeLocation = UNKNOWN_CODE_LOCATION,
            methodId = MethodIds.getMethodId(className, methodName, methodDesc),
            methodParams = emptyArray(),
            callType = MethodCallTracePoint.CallType.ACTOR
        ))
    }

    fun finishAndDumpTrace() {
        val tds = ArrayList(threads.values)
        threads.clear()

        val createDumpFile: (String?) -> PrintStream = { dumpPath: String? ->
            if (dumpPath == null) {
                System.out
            } else  {
                val f = File(dumpPath)
                f.parentFile?.mkdirs()
                f.createNewFile()
                PrintStream(f)
            }
        }

        // Merge all traces. Mergesort is possible as optimization
        val totalTraceArray = mutableListOf<TracePoint>()
        tds.forEach { totalTraceArray.addAll(it.traceCollector!!.trace) }
        totalTraceArray.sortWith { a, b -> a.eventId.compareTo(b.eventId) }

        val totalTrace = Trace(totalTraceArray, listOf("Thread"))

        // Filter & prepare trace to "graph"
        val graph = try {
            traceToCollapsedGraph(totalTrace, analysisProfile, null)
        } catch (t: Throwable) {
            throw t
        }
        val nodeList = graph.flattenNodes(VerboseTraceFlattenPolicy())

        // saving human-readable format (use the file name specified by the user)
        createDumpFile(traceDumpPath).use { printStream ->
            val sb = StringBuilder()
            sb.appendTraceTable("Trace started from $methodName", totalTrace, null, nodeList)
            printStream.print(sb.toString())
        }

        // saving csv format (same filename as 'traceDumpPath' but with '.csv' file extension)
        val traceCsvDumpPath = traceDumpPath?.substringBeforeLast(".")?.let { "$it.csv" }
        createDumpFile(traceCsvDumpPath).use { printStream ->
            flattenedTraceGraphToCSV(nodeList).forEach {
                printStream.println(it)
            }
        }
    }

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
